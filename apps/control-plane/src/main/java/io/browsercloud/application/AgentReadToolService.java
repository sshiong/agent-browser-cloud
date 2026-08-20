package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.ToolCapabilityUseJpaRepository;
import java.net.IDN;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** 只读 Tool Service；不接受 Agent 自报授权，逐调用验签并原子消费 Token。 */
@Service
public class AgentReadToolService {

  private final BrowserStateRepository stateRepository;
  private final ToolCapabilityUseJpaRepository capabilityUses;
  private final AgentCapabilityTokenService capabilityTokens;
  private final ObjectMapper objectMapper;

  public AgentReadToolService(
      BrowserStateRepository stateRepository,
      ToolCapabilityUseJpaRepository capabilityUses,
      AgentCapabilityTokenService capabilityTokens,
      ObjectMapper objectMapper) {
    this.stateRepository = stateRepository;
    this.capabilityUses = capabilityUses;
    this.capabilityTokens = capabilityTokens;
    this.objectMapper = objectMapper;
  }

  public ToolExecutionResult execute(
      String tenantId,
      SessionContext session,
      String taskId,
      String intentId,
      PlanStep step,
      Set<String> allowedDomains,
      Instant now) {
    if (step.toolId() != ToolId.GET_CURRENT_STATE
        && step.toolId() != ToolId.GET_URL
        && step.toolId() != ToolId.GET_PAGE_SUMMARY) {
      throw new ToolExecutionException("TOOL_NOT_IMPLEMENTED");
    }
    var snapshot =
        stateRepository
            .find(session.sessionId())
            .filter(value -> value.tenantId().equals(tenantId))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .orElseThrow(() -> new ToolExecutionException("STATE_CONTEXT_MISMATCH"));
    var state = snapshot.state();
    if (!state.stateQuality().equals("COMPLETE") && !state.stateQuality().equals("DEPTH_LIMITED")) {
      throw new ToolExecutionException("STATE_QUALITY_NOT_EXECUTABLE");
    }
    var currentDomain = domainOf(state.url());
    var dataScope = "BROWSER_STATE_METADATA";
    var claims =
        step.toolId() == ToolId.GET_CURRENT_STATE
            ? capabilityTokens.verify(
                step.capabilityToken(),
                tenantId,
                session.sessionId(),
                intentId,
                taskId,
                step.toolId(),
                currentDomain,
                dataScope,
                now)
            : capabilityTokens.verifyWithinAllowedDomains(
                step.capabilityToken(),
                tenantId,
                session.sessionId(),
                intentId,
                taskId,
                step.toolId(),
                currentDomain,
                allowedDomains,
                dataScope,
                now);
    if (capabilityUses.claim(
            claims.tokenId(), tenantId, session.sessionId(), taskId, step.toolId().name(), now)
        != 1) {
      throw new ToolExecutionException("CAPABILITY_TOKEN_REPLAYED");
    }

    Map<String, Object> output =
        switch (step.toolId()) {
          case GET_CURRENT_STATE -> stateMetadata(state);
          case GET_URL -> Map.of("url", safeUrl(state.url()), "domain", currentDomain);
          case GET_PAGE_SUMMARY -> pageSummary(state);
          default -> throw new ToolExecutionException("TOOL_NOT_IMPLEMENTED");
        };
    var resultHash = PromptSecurityService.sha256(write(output));
    return new ToolExecutionResult(
        step.stepId(), step.toolId(), "VERIFIED", resultHash, output, step.verification(), now);
  }

  private static Map<String, Object> stateMetadata(
      io.browsercloud.coordinator.NodeEvent.StateUpdated state) {
    var output = new LinkedHashMap<String, Object>();
    output.put("stateVersion", state.stateVersion());
    output.put("targetRevision", state.targetRevision());
    output.put("stateQuality", state.stateQuality());
    output.put("stateHash", state.stateHash());
    output.put("interactiveTargetCount", state.targets().size());
    return output;
  }

  private static Map<String, Object> pageSummary(
      io.browsercloud.coordinator.NodeEvent.StateUpdated state) {
    var roleCounts = new TreeMap<String, Integer>();
    state.targets().forEach(target -> roleCounts.merge(target.role(), 1, Integer::sum));
    var targetNames =
        state.targets().stream()
            .filter(target -> target.visible() && target.enabled())
            .map(target -> AgentDataMinimizer.redact(target.name()))
            .filter(name -> !name.isBlank())
            .limit(20)
            .toList();
    var output = new LinkedHashMap<String, Object>();
    output.put("url", safeUrl(state.url()));
    output.put("title", AgentDataMinimizer.redact(state.title()));
    output.put("stateQuality", state.stateQuality());
    output.put("interactiveTargetCount", state.targets().size());
    output.put("roleCounts", roleCounts);
    output.put("targetNames", targetNames);
    return output;
  }

  private static String safeUrl(String value) {
    try {
      var uri = URI.create(value);
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toASCIIString();
    } catch (Exception exception) {
      throw new ToolExecutionException("STATE_URL_INVALID");
    }
  }

  private static String domainOf(String value) {
    try {
      var host = URI.create(value).getHost();
      if (host == null) {
        throw new ToolExecutionException("STATE_URL_INVALID");
      }
      return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      throw new ToolExecutionException("STATE_URL_INVALID");
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to hash Tool result", exception);
    }
  }

  public static final class ToolExecutionException extends RuntimeException {
    public ToolExecutionException(String message) {
      super(message);
    }
  }
}
