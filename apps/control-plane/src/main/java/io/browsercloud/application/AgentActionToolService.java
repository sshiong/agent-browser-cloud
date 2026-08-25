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
      Set.of(
          ToolId.CLICK_TARGET,
          ToolId.DOUBLE_CLICK_TARGET,
          ToolId.RIGHT_CLICK_TARGET,
          ToolId.HOVER_TARGET,
          ToolId.CLEAR_TARGET,
          ToolId.CHECK_TARGET,
          ToolId.UNCHECK_TARGET,
          ToolId.TYPE_TEXT,
          ToolId.FILL,
          ToolId.PASTE_AGENT_CLIPBOARD,
          ToolId.SCROLL,
          ToolId.WAIT_FOR,
          ToolId.OPEN_TAB,
          ToolId.SWITCH_TAB,
          ToolId.CLOSE_TAB,
          ToolId.ACCEPT_DIALOG,
          ToolId.DISMISS_DIALOG,
          ToolId.PRESS_KEY,
          ToolId.SELECT_OPTION,
          ToolId.DRAG_TARGET,
          ToolId.DROP_TARGET,
          ToolId.SWIPE_TARGET,
          ToolId.MOUSE_MOVE,
          ToolId.MOUSE_DOWN,
          ToolId.MOUSE_UP,
          ToolId.MOUSE_WHEEL,
          ToolId.KEY_DOWN,
          ToolId.KEY_UP,
          ToolId.TOUCH_START,
          ToolId.TOUCH_MOVE,
          ToolId.TOUCH_END,
          ToolId.EXECUTE_ACTIONS);

  private final BrowserStateRepository stateRepository;
  private final ToolCapabilityUseJpaRepository capabilityUses;
  private final AgentCapabilityTokenService capabilityTokens;
  private final NodeCommandGateway nodeCommandGateway;
  private final AgentControlPolicyService controlPolicies;

  public AgentActionToolService(
      BrowserStateRepository stateRepository,
      ToolCapabilityUseJpaRepository capabilityUses,
      AgentCapabilityTokenService capabilityTokens,
      NodeCommandGateway nodeCommandGateway,
      AgentControlPolicyService controlPolicies) {
    this.stateRepository = stateRepository;
    this.capabilityUses = capabilityUses;
    this.capabilityTokens = capabilityTokens;
    this.nodeCommandGateway = nodeCommandGateway;
    this.controlPolicies = controlPolicies;
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
    if (!Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())
        && !(state.stateQuality().equals("DEGRADED")
            && state.nativeDialogEvidenceFresh()
            && containsDialogAction(step))) {
      throw new ActionToolException("STATE_QUALITY_NOT_EXECUTABLE");
    }
    validateInput(step, state, controlPolicies.require(session.sessionId(), tenantId));
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
      PlanStep step,
      io.browsercloud.coordinator.NodeEvent.StateUpdated state,
      AgentControlPolicyService.Policy policy) {
    var input = step.input();
    switch (step.toolId()) {
      case CLICK_TARGET,
          DOUBLE_CLICK_TARGET,
          RIGHT_CLICK_TARGET,
          HOVER_TARGET,
          CLEAR_TARGET,
          CHECK_TARGET,
          UNCHECK_TARGET,
          TYPE_TEXT,
          FILL,
          PASTE_AGENT_CLIPBOARD,
          PRESS_KEY,
          SELECT_OPTION,
          DRAG_TARGET,
          DROP_TARGET,
          SWIPE_TARGET,
          MOUSE_MOVE,
          MOUSE_DOWN,
          MOUSE_UP,
          MOUSE_WHEEL,
          KEY_DOWN,
          KEY_UP,
          TOUCH_START,
          TOUCH_MOVE,
          TOUCH_END -> {
        if (input.tabId() != null
            || input.tabUrl() != null
            || input.dialogId() != null
            || input.targetRef() == null
            || input.targetRef().isBlank()
            || input.targetRevision() == null
            || input.targetRevision() != state.targetRevision()) {
          throw new ActionToolException("TARGET_REVISION_MISMATCH");
        }
        var target =
            state.targets().stream()
                .filter(
                    candidate ->
                        candidate.targetRef().equals(input.targetRef())
                            || input.targetRef().equals(candidate.elementId()))
                .findFirst()
                .orElseThrow(() -> new ActionToolException("TARGET_NOT_FOUND"));
        if (!target.visible()
            || !target.enabled()
            || target.bounds() == null
            || (target.elementId() != null && (target.occluded() || !target.inViewport()))) {
          throw new ActionToolException("TARGET_NOT_ACTIONABLE");
        }
        if (isTextInput(step.toolId())) {
          var sensitiveData =
              input.dataClass() == ActionDataClass.CREDENTIAL
                  || input.dataClass() == ActionDataClass.OTP;
          if (target.sensitive()
              && (!policy.autonomous() || !input.allowSensitiveTarget() || !sensitiveData)) {
            throw new ActionToolException("SENSITIVE_TARGET_FORBIDDEN");
          }
          if (input.allowSensitiveTarget()
              && (!policy.autonomous()
                  || !sensitiveData
                  || input.maximumAttempts() < 1
                  || input.maximumAttempts() > policy.sensitiveInputMaximumAttempts())) {
            throw new ActionToolException("AUTONOMOUS_SENSITIVE_INPUT_POLICY_MISMATCH");
          }
          if (!Set.of("textbox", "combobox").contains(target.role())) {
            throw new ActionToolException("TYPE_TARGET_ROLE_INVALID");
          }
          if (step.toolId() == ToolId.SELECT_OPTION && !"combobox".equals(target.role())) {
            throw new ActionToolException("SELECT_TARGET_ROLE_INVALID");
          }
          if (input.sealedPayload() == null
              || input.sealedPayload().isBlank()
              || input.payloadHash() == null
              || input.payloadLength() == null
              || input.payloadLength() < 1
              || input.payloadLength() > 2_000) {
            throw new ActionToolException("TYPE_PAYLOAD_INVALID");
          }
          if (hasAdvancedInput(input)) {
            throw new ActionToolException("TEXT_ACTION_ADVANCED_INPUT_FORBIDDEN");
          }
        } else {
          if (input.sealedPayload() != null
              || input.dataClass() != null
              || input.payloadHash() != null
              || input.payloadLength() != null
              || input.scrollDeltaY() != null
              || input.waitCondition() != null
              || input.timeoutMs() != null) {
            throw new ActionToolException("TARGET_ACTION_PAYLOAD_FORBIDDEN");
          }
          validateTargetActionRole(step.toolId(), target);
          validateAdvancedTargetInput(step.toolId(), input, state);
        }
      }
      case SCROLL -> {
        if (input.tabId() != null
            || input.tabUrl() != null
            || input.dialogId() != null
            || input.scrollDeltaY() == null
            || Math.abs(input.scrollDeltaY()) < 100
            || Math.abs(input.scrollDeltaY()) > 2_000) {
          throw new ActionToolException("SCROLL_DELTA_INVALID");
        }
      }
      case WAIT_FOR -> {
        if (input.tabId() != null
            || input.tabUrl() != null
            || input.dialogId() != null
            || input.waitCondition() == null
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
      case OPEN_TAB -> {
        if (input.tabId() != null
            || input.dialogId() != null
            || invalidTabUrl(input.tabUrl())
            || hasNonTabPayload(input)) {
          throw new ActionToolException("OPEN_TAB_INPUT_INVALID");
        }
      }
      case SWITCH_TAB, CLOSE_TAB -> {
        if (input.tabId() == null
            || input.tabId().isBlank()
            || input.tabUrl() != null
            || input.dialogId() != null
            || hasNonTabPayload(input)
            || state.tabs().stream().noneMatch(tab -> tab.tabId().equals(input.tabId()))) {
          throw new ActionToolException("TAB_BINDING_INVALID");
        }
        if (step.toolId() == ToolId.CLOSE_TAB && state.tabs().size() <= 1) {
          throw new ActionToolException("LAST_TAB_CLOSE_FORBIDDEN");
        }
      }
      case ACCEPT_DIALOG, DISMISS_DIALOG ->
          validateDialogInput(step.toolId(), input, state, policy);
      case EXECUTE_ACTIONS -> {
        if (input.actions().isEmpty() || input.actions().size() > 20) {
          throw new ActionToolException("BATCH_ACTION_COUNT_INVALID");
        }
        for (var action : input.actions()) {
          validateBatchAction(action, state, policy);
        }
      }
      default -> throw new ActionToolException("ACTION_STEP_INVALID");
    }
  }

  private static void validateBatchAction(
      ActionInput input,
      io.browsercloud.coordinator.NodeEvent.StateUpdated state,
      AgentControlPolicyService.Policy policy) {
    if (input.actionId() == null || !input.actionId().matches("^action_[1-9][0-9]?$")) {
      throw new ActionToolException("BATCH_ACTION_ID_INVALID");
    }
    if (!Set.of(
            ToolId.CLICK_TARGET,
            ToolId.DOUBLE_CLICK_TARGET,
            ToolId.RIGHT_CLICK_TARGET,
            ToolId.HOVER_TARGET,
            ToolId.CLEAR_TARGET,
            ToolId.CHECK_TARGET,
            ToolId.UNCHECK_TARGET,
            ToolId.TYPE_TEXT,
            ToolId.FILL,
            ToolId.PASTE_AGENT_CLIPBOARD,
            ToolId.SCROLL,
            ToolId.WAIT_FOR,
            ToolId.OPEN_TAB,
            ToolId.SWITCH_TAB,
            ToolId.CLOSE_TAB,
            ToolId.ACCEPT_DIALOG,
            ToolId.DISMISS_DIALOG,
            ToolId.PRESS_KEY,
            ToolId.SELECT_OPTION,
            ToolId.DRAG_TARGET,
            ToolId.DROP_TARGET,
            ToolId.SWIPE_TARGET,
            ToolId.MOUSE_MOVE,
            ToolId.MOUSE_DOWN,
            ToolId.MOUSE_UP,
            ToolId.MOUSE_WHEEL,
            ToolId.KEY_DOWN,
            ToolId.KEY_UP,
            ToolId.TOUCH_START,
            ToolId.TOUCH_MOVE,
            ToolId.TOUCH_END)
        .contains(input.toolId())) {
      throw new ActionToolException("BATCH_ACTION_TOOL_FORBIDDEN");
    }
    var stepInput =
        new StepInput(
            input.targetRef(),
            input.targetRevision(),
            input.sealedPayload(),
            input.payloadHash(),
            input.payloadLength(),
            input.dataClass(),
            input.scrollDeltaY(),
            input.waitCondition(),
            input.timeoutMs(),
            input.allowSensitiveTarget(),
            input.maximumAttempts(),
            java.util.List.of(),
            true,
            input.tabId(),
            input.tabUrl(),
            input.dialogId(),
            input.endTargetRef(),
            input.endElementId(),
            input.key(),
            input.button(),
            input.deltaX(),
            input.deltaY(),
            input.durationMs());
    var step =
        new PlanStep(
            input.actionId(),
            input.toolId(),
            RiskClass.R1_LOW_RISK_CHANGE,
            null,
            stepInput,
            "batch",
            java.util.List.of(),
            TrustLevel.TRUSTED,
            java.util.List.of(),
            false,
            ExecutionStrategy.SEMANTIC_DOM,
            "COMPLETE",
            "BATCH",
            "",
            "");
    validateInput(step, state, policy);
  }

  private static String dataScope(PlanStep step) {
    return switch (step.toolId()) {
      case CLICK_TARGET -> "TARGET_ACTION";
      case DOUBLE_CLICK_TARGET,
              RIGHT_CLICK_TARGET,
              HOVER_TARGET,
              CLEAR_TARGET,
              CHECK_TARGET,
              UNCHECK_TARGET,
              PRESS_KEY,
              DRAG_TARGET,
              DROP_TARGET,
              SWIPE_TARGET,
              MOUSE_MOVE,
              MOUSE_DOWN,
              MOUSE_UP,
              MOUSE_WHEEL,
              KEY_DOWN,
              KEY_UP,
              TOUCH_START,
              TOUCH_MOVE,
              TOUCH_END ->
          "TARGET_ACTION";
      case TYPE_TEXT, FILL, PASTE_AGENT_CLIPBOARD, SELECT_OPTION ->
          switch (step.input().dataClass()) {
            case PII -> "FORM_INPUT_PII";
            case CREDENTIAL -> "FORM_INPUT_CREDENTIAL";
            case OTP -> "FORM_INPUT_OTP";
            default -> "FORM_INPUT_PUBLIC";
          };
      case SCROLL -> "VIEWPORT_ACTION";
      case WAIT_FOR -> "STATE_OBSERVATION";
      case OPEN_TAB -> "TAB_OPEN:" + PromptSecurityService.sha256(step.input().tabUrl());
      case SWITCH_TAB, CLOSE_TAB ->
          "TAB_TARGET:" + PromptSecurityService.sha256(step.input().tabId());
      case ACCEPT_DIALOG ->
          "NATIVE_DIALOG_ACCEPT:"
              + (step.input().dataClass() == null ? "NONE" : step.input().dataClass().name())
              + ":"
              + PromptSecurityService.sha256(step.input().dialogId());
      case DISMISS_DIALOG ->
          "NATIVE_DIALOG_DISMISS:" + PromptSecurityService.sha256(step.input().dialogId());
      case EXECUTE_ACTIONS -> "BATCH_ACTIONS";
      default -> throw new ActionToolException("ACTION_STEP_INVALID");
    };
  }

  private static boolean invalidTabUrl(String value) {
    if (value == null || value.isBlank()) return true;
    try {
      var uri = URI.create(value);
      return uri.getHost() == null
          || uri.getUserInfo() != null
          || !("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()));
    } catch (IllegalArgumentException exception) {
      return true;
    }
  }

  private static boolean hasNonTabPayload(StepInput input) {
    return input.targetRef() != null
        || input.targetRevision() != null
        || input.sealedPayload() != null
        || input.payloadHash() != null
        || input.payloadLength() != null
        || input.dataClass() != null
        || input.scrollDeltaY() != null
        || input.waitCondition() != null
        || input.timeoutMs() != null
        || input.dialogId() != null
        || hasAdvancedInput(input)
        || !input.actions().isEmpty();
  }

  private static boolean containsDialogAction(PlanStep step) {
    if (Set.of(ToolId.ACCEPT_DIALOG, ToolId.DISMISS_DIALOG).contains(step.toolId())) return true;
    return step.toolId() == ToolId.EXECUTE_ACTIONS
        && step.input().actions().stream()
            .anyMatch(
                action ->
                    Set.of(ToolId.ACCEPT_DIALOG, ToolId.DISMISS_DIALOG).contains(action.toolId()));
  }

  private static void validateDialogInput(
      ToolId toolId,
      StepInput input,
      io.browsercloud.coordinator.NodeEvent.StateUpdated state,
      AgentControlPolicyService.Policy policy) {
    if (input.dialogId() == null
        || input.dialogId().isBlank()
        || input.targetRef() != null
        || input.targetRevision() != null
        || input.tabId() != null
        || input.tabUrl() != null
        || input.scrollDeltaY() != null
        || input.waitCondition() != null
        || input.timeoutMs() != null
        || hasAdvancedInput(input)
        || !input.actions().isEmpty()
        || !state.nativeDialogEvidenceFresh()) {
      throw new ActionToolException("NATIVE_DIALOG_BINDING_INVALID");
    }
    var dialog =
        state.nativeDialogs().stream()
            .filter(candidate -> candidate.dialogId().equals(input.dialogId()))
            .findFirst()
            .orElseThrow(() -> new ActionToolException("NATIVE_DIALOG_NOT_FOUND"));
    var carriesPrompt = input.sealedPayload() != null;
    if (toolId == ToolId.DISMISS_DIALOG && carriesPrompt) {
      throw new ActionToolException("DIALOG_DISMISS_PROMPT_FORBIDDEN");
    }
    if (carriesPrompt) {
      if (!dialog.dialogType().equals("PROMPT")
          || input.payloadHash() == null
          || input.payloadLength() == null
          || input.payloadLength() < 1
          || input.payloadLength() > 2_000
          || input.dataClass() == null) {
        throw new ActionToolException("DIALOG_PROMPT_PAYLOAD_INVALID");
      }
      var sensitive =
          input.dataClass() == ActionDataClass.CREDENTIAL
              || input.dataClass() == ActionDataClass.OTP;
      if (sensitive
          && (!policy.autonomous()
              || !input.allowSensitiveTarget()
              || input.maximumAttempts() != 1)) {
        throw new ActionToolException("AUTONOMOUS_DIALOG_PROMPT_POLICY_MISMATCH");
      }
    } else if (input.payloadHash() != null
        || input.payloadLength() != null
        || input.dataClass() != null
        || input.allowSensitiveTarget()) {
      throw new ActionToolException("DIALOG_PROMPT_PAYLOAD_INVALID");
    }
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

  private static boolean isTextInput(ToolId toolId) {
    return toolId == ToolId.TYPE_TEXT
        || toolId == ToolId.FILL
        || toolId == ToolId.PASTE_AGENT_CLIPBOARD
        || toolId == ToolId.SELECT_OPTION;
  }

  private static boolean hasAdvancedInput(StepInput input) {
    return input.endTargetRef() != null
        || input.endElementId() != null
        || input.key() != null
        || input.button() != null
        || input.deltaX() != null
        || input.deltaY() != null
        || input.durationMs() != null;
  }

  private static void validateAdvancedTargetInput(
      ToolId toolId, StepInput input, io.browsercloud.coordinator.NodeEvent.StateUpdated state) {
    var keyAction = Set.of(ToolId.PRESS_KEY, ToolId.KEY_DOWN, ToolId.KEY_UP).contains(toolId);
    var buttonAction = Set.of(ToolId.MOUSE_DOWN, ToolId.MOUSE_UP).contains(toolId);
    var deltaAction = Set.of(ToolId.SWIPE_TARGET, ToolId.MOUSE_WHEEL).contains(toolId);
    if (keyAction) {
      if (input.key() == null
          || input.key().isBlank()
          || input.key().length() > 32
          || input.endTargetRef() != null
          || input.button() != null
          || input.deltaX() != null
          || input.deltaY() != null
          || input.durationMs() != null) {
        throw new ActionToolException("KEY_INPUT_INVALID");
      }
      return;
    }
    if (toolId == ToolId.DRAG_TARGET) {
      if (input.endTargetRef() == null
          || input.endTargetRef().isBlank()
          || input.key() != null
          || input.button() != null
          || input.deltaX() != null
          || input.deltaY() != null
          || invalidDuration(input.durationMs())) {
        throw new ActionToolException("DRAG_INPUT_INVALID");
      }
      var destination =
          state.targets().stream()
              .filter(
                  candidate ->
                      candidate.targetRef().equals(input.endTargetRef())
                          || input.endTargetRef().equals(candidate.elementId()))
              .findFirst()
              .orElseThrow(() -> new ActionToolException("DRAG_DESTINATION_NOT_FOUND"));
      if (!destination.visible()
          || !destination.enabled()
          || destination.bounds() == null
          || destination.occluded()
          || !destination.inViewport()) {
        throw new ActionToolException("DRAG_DESTINATION_NOT_ACTIONABLE");
      }
      return;
    }
    if (deltaAction) {
      if (input.endTargetRef() != null
          || input.key() != null
          || input.button() != null
          || (zero(input.deltaX()) && zero(input.deltaY()))
          || invalidDelta(input.deltaX())
          || invalidDelta(input.deltaY())
          || (toolId == ToolId.SWIPE_TARGET && invalidDuration(input.durationMs()))
          || (toolId == ToolId.MOUSE_WHEEL && input.durationMs() != null)) {
        throw new ActionToolException(
            toolId == ToolId.SWIPE_TARGET ? "SWIPE_INPUT_INVALID" : "WHEEL_INPUT_INVALID");
      }
      return;
    }
    if (buttonAction) {
      if (input.endTargetRef() != null
          || input.key() != null
          || input.deltaX() != null
          || input.deltaY() != null
          || input.durationMs() != null
          || input.button() == null
          || input.button() < 0
          || input.button() > 2) {
        throw new ActionToolException("MOUSE_BUTTON_INPUT_INVALID");
      }
      return;
    }
    if (Set.of(
            ToolId.DROP_TARGET,
            ToolId.MOUSE_MOVE,
            ToolId.TOUCH_START,
            ToolId.TOUCH_MOVE,
            ToolId.TOUCH_END)
        .contains(toolId)) {
      if (hasAdvancedInput(input)) {
        throw new ActionToolException("POINTER_INPUT_INVALID");
      }
      return;
    }
    if (hasAdvancedInput(input)) {
      throw new ActionToolException("TARGET_ACTION_ADVANCED_INPUT_FORBIDDEN");
    }
  }

  private static boolean zero(Integer value) {
    return value == null || value == 0;
  }

  private static boolean invalidDelta(Integer value) {
    return value != null && Math.abs(value) > 4_000;
  }

  private static boolean invalidDuration(Integer value) {
    return value != null && (value < 0 || value > 5_000);
  }

  private static void validateTargetActionRole(
      ToolId toolId, io.browsercloud.coordinator.NodeEvent.InteractiveTarget target) {
    if (toolId == ToolId.CLEAR_TARGET && !Set.of("textbox", "combobox").contains(target.role())) {
      throw new ActionToolException("CLEAR_TARGET_ROLE_INVALID");
    }
    if (toolId == ToolId.CHECK_TARGET && !Set.of("checkbox", "radio").contains(target.role())) {
      throw new ActionToolException("CHECK_TARGET_ROLE_INVALID");
    }
    if (toolId == ToolId.UNCHECK_TARGET && !"checkbox".equals(target.role())) {
      throw new ActionToolException("UNCHECK_TARGET_ROLE_INVALID");
    }
    if (Set.of(ToolId.CHECK_TARGET, ToolId.UNCHECK_TARGET).contains(toolId)
        && target.checked() == null) {
      throw new ActionToolException("TARGET_CHECKED_STATE_UNAVAILABLE");
    }
  }

  public record PendingAction(long baseStateVersion, String baseStateHash, Instant deadline) {}

  public static final class ActionToolException extends RuntimeException {
    public ActionToolException(String message) {
      super(message);
    }
  }
}
