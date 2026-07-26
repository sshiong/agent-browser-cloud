package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.ToolCapabilityUseJpaRepository;
import java.net.IDN;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 导航 Tool Service；Capability 在 Control Plane 消费，Node 只收到最小化导航命令。 */
@Service
public class AgentNavigationToolService {

  private final BrowserStateRepository stateRepository;
  private final ToolCapabilityUseJpaRepository capabilityUses;
  private final AgentCapabilityTokenService capabilityTokens;
  private final NodeCommandGateway nodeCommandGateway;

  public AgentNavigationToolService(
      BrowserStateRepository stateRepository,
      ToolCapabilityUseJpaRepository capabilityUses,
      AgentCapabilityTokenService capabilityTokens,
      NodeCommandGateway nodeCommandGateway) {
    this.stateRepository = stateRepository;
    this.capabilityUses = capabilityUses;
    this.capabilityTokens = capabilityTokens;
    this.nodeCommandGateway = nodeCommandGateway;
  }

  public PendingNavigation authorizeAndQueue(
      String tenantId,
      SessionContext session,
      ExclusiveOperation operation,
      String taskId,
      String intentId,
      PlanStep step,
      Instant now) {
    if (step.toolId() != ToolId.NAVIGATE
        || step.targetUrl() == null
        || step.targetUrl().isBlank()) {
      throw new NavigationToolException("NAVIGATION_STEP_INVALID");
    }
    var targetDomain = domainOf(step.targetUrl());
    var claims =
        capabilityTokens.verify(
            step.capabilityToken(),
            tenantId,
            session.sessionId(),
            intentId,
            taskId,
            ToolId.NAVIGATE,
            targetDomain,
            "NAVIGATION",
            now);
    if (capabilityUses.claim(
            claims.tokenId(), tenantId, session.sessionId(), taskId, ToolId.NAVIGATE.name(), now)
        != 1) {
      throw new NavigationToolException("CAPABILITY_TOKEN_REPLAYED");
    }
    var state =
        stateRepository
            .find(session.sessionId())
            .filter(snapshot -> snapshot.tenantId().equals(tenantId))
            .filter(snapshot -> snapshot.contextEpoch() == session.contextEpoch())
            .map(BrowserStateRepository.Snapshot::state)
            .orElse(null);
    var baseStateVersion = state == null ? 0L : state.stateVersion();
    var baseContentHash = state == null ? "" : state.stateHash();
    nodeCommandGateway.send(
        NodeCommands.agentNavigate(
            session, operation, taskId, step.stepId(), step.targetUrl(), baseStateVersion));
    return new PendingNavigation(baseStateVersion, baseContentHash, now.plusSeconds(15));
  }

  public record PendingNavigation(
      long baseStateVersion, String baseContentHash, Instant deadline) {}

  static String domainOf(String value) {
    try {
      var uri = URI.create(value);
      if (!java.util.Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw new NavigationToolException("NAVIGATION_URL_INVALID");
      }
      return IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new NavigationToolException("NAVIGATION_URL_INVALID");
    }
  }

  public static final class NavigationToolException extends RuntimeException {
    public NavigationToolException(String message) {
      super(message);
    }
  }
}
