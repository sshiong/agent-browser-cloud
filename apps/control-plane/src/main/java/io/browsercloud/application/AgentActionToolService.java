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
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 结构化浏览器动作 Tool Service。
 *
 * <p>每次调用都重新绑定权威 Browser State、校验并单次消费 Capability。文本密文只进入 Outbox，Dispatcher 在发送 Node 前的最后一刻解封。
 */
@Service
public class AgentActionToolService {

  private static final Duration COLLABORATIVE_INPUT_WAIT = Duration.ofMinutes(30);

  private static final Set<ToolId> SUPPORTED =
      Set.of(ToolId.CLICK_TARGET, ToolId.TYPE_TEXT, ToolId.SCROLL, ToolId.WAIT_FOR);

  private final BrowserStateRepository stateRepository;
  private final ToolCapabilityUseJpaRepository capabilityUses;
  private final AgentCapabilityTokenService capabilityTokens;
  private final NodeCommandGateway nodeCommandGateway;

  public AgentActionToolService(
      BrowserStateRepository stateRepository,
      ToolCapabilityUseJpaRepository capabilityUses,
      AgentCapabilityTokenService capabilityTokens,
      NodeCommandGateway nodeCommandGateway) {
    this.stateRepository = stateRepository;
    this.capabilityUses = capabilityUses;
    this.capabilityTokens = capabilityTokens;
    this.nodeCommandGateway = nodeCommandGateway;
  }

  public PendingAction authorizeAndQueue(
      String tenantId,
      SessionContext session,
      ExclusiveOperation operation,
      String taskId,
      String intentId,
      PlanStep step,
      Instant now) {
    if (!SUPPORTED.contains(step.toolId()) || step.input() == null) {
      throw new ActionToolException("ACTION_STEP_INVALID");
    }
    var snapshot =
        stateRepository
            .find(session.sessionId())
            .filter(value -> value.tenantId().equals(tenantId))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .orElseThrow(() -> new ActionToolException("STATE_CONTEXT_MISMATCH"));
    var state = snapshot.state();
    if (!Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      throw new ActionToolException("STATE_QUALITY_NOT_EXECUTABLE");
    }
    validateInput(step, state);
    var currentDomain = domainOf(state.url());
    var claims =
        capabilityTokens.verify(
            step.capabilityToken(),
            tenantId,
            session.sessionId(),
            intentId,
            taskId,
            step.toolId(),
            currentDomain,
            dataScope(step),
            now);
    if (capabilityUses.claim(
            claims.tokenId(), tenantId, session.sessionId(), taskId, step.toolId().name(), now)
        != 1) {
      throw new ActionToolException("CAPABILITY_TOKEN_REPLAYED");
    }
    nodeCommandGateway.send(
        NodeCommands.agentAction(
            session, operation, taskId, step, state.stateVersion(), state.stateHash()));
    return new PendingAction(
        state.stateVersion(), state.stateHash(), now.plus(COLLABORATIVE_INPUT_WAIT));
  }

  private static void validateInput(
      PlanStep step, io.browsercloud.coordinator.NodeEvent.StateUpdated state) {
    var input = step.input();
    switch (step.toolId()) {
      case CLICK_TARGET, TYPE_TEXT -> {
        if (input.targetRef() == null
            || input.targetRef().isBlank()
            || input.targetRevision() == null
            || input.targetRevision() != state.targetRevision()) {
          throw new ActionToolException("TARGET_REVISION_MISMATCH");
        }
        var target =
            state.targets().stream()
                .filter(candidate -> candidate.targetRef().equals(input.targetRef()))
                .findFirst()
                .orElseThrow(() -> new ActionToolException("TARGET_NOT_FOUND"));
        if (!target.visible() || !target.enabled() || target.bounds() == null) {
          throw new ActionToolException("TARGET_NOT_ACTIONABLE");
        }
        if (step.toolId() == ToolId.TYPE_TEXT) {
          if (target.sensitive()) {
            throw new ActionToolException("SENSITIVE_TARGET_FORBIDDEN");
          }
          if (!Set.of("textbox", "combobox").contains(target.role())) {
            throw new ActionToolException("TYPE_TARGET_ROLE_INVALID");
          }
          if (input.sealedPayload() == null
              || input.sealedPayload().isBlank()
              || input.payloadHash() == null
              || input.payloadLength() == null
              || input.payloadLength() < 1
              || input.payloadLength() > 2_000) {
            throw new ActionToolException("TYPE_PAYLOAD_INVALID");
          }
        } else if (input.sealedPayload() != null) {
          throw new ActionToolException("CLICK_PAYLOAD_FORBIDDEN");
        }
      }
      case SCROLL -> {
        if (input.scrollDeltaY() == null
            || Math.abs(input.scrollDeltaY()) < 100
            || Math.abs(input.scrollDeltaY()) > 2_000) {
          throw new ActionToolException("SCROLL_DELTA_INVALID");
        }
      }
      case WAIT_FOR -> {
        if (input.waitCondition() == null
            || input.timeoutMs() == null
            || input.timeoutMs() < 100
            || input.timeoutMs() > 10_000) {
          throw new ActionToolException("WAIT_CONDITION_INVALID");
        }
        if (input.waitCondition() == WaitCondition.TARGET_PRESENT
            && (input.targetRef() == null || input.targetRef().isBlank())) {
          throw new ActionToolException("WAIT_TARGET_REQUIRED");
        }
      }
      default -> throw new ActionToolException("ACTION_STEP_INVALID");
    }
  }

  private static String dataScope(PlanStep step) {
    return switch (step.toolId()) {
      case CLICK_TARGET -> "TARGET_ACTION";
      case TYPE_TEXT ->
          step.input().dataClass() == ActionDataClass.PII ? "FORM_INPUT_PII" : "FORM_INPUT_PUBLIC";
      case SCROLL -> "VIEWPORT_ACTION";
      case WAIT_FOR -> "STATE_OBSERVATION";
      default -> throw new ActionToolException("ACTION_STEP_INVALID");
    };
  }

  private static String domainOf(String value) {
    try {
      var host = URI.create(value).getHost();
      if (host == null) {
        throw new ActionToolException("STATE_URL_INVALID");
      }
      return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      throw new ActionToolException("STATE_URL_INVALID");
    }
  }

  public record PendingAction(long baseStateVersion, String baseStateHash, Instant deadline) {}

  public static final class ActionToolException extends RuntimeException {
    public ActionToolException(String message) {
      super(message);
    }
  }
}
