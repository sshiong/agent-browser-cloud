package io.browsercloud.domain.agent;

import java.time.Instant;
import java.util.List;

/** Phase 4 Agent 安全内核使用的稳定领域契约。 */
public final class AgentModels {

  private AgentModels() {}

  public enum InstructionSourceType {
    SYSTEM,
    PLATFORM_POLICY,
    TENANT_POLICY,
    USER_AUTHORIZATION,
    USER_REQUEST,
    APPROVED_APPLICATION_RULE,
    APPLICATION_DATA,
    EMAIL,
    DOCUMENT,
    WEB_CONTENT,
    THIRD_PARTY_WIDGET
  }

  public enum TrustLevel {
    TRUSTED,
    RESTRICTED,
    UNTRUSTED
  }

  public enum RiskClass {
    R0_READ_ONLY,
    R1_LOW_RISK_CHANGE,
    R2_DATA_CHANGE,
    R3_ACCOUNT_CHANGE,
    R4_FINANCIAL,
    R5_SECURITY
  }

  public enum IntentDecision {
    ALLOWED,
    CONFIRM_REQUIRED,
    FORBIDDEN
  }

  public enum AgentControlMode {
    SAFE,
    AUTONOMOUS
  }

  public enum TaskState {
    PLANNED,
    QUEUED,
    AWAITING_REVIEW,
    AWAITING_CONFIRMATION,
    BLOCKED,
    RUNNING,
    PAUSED_BY_RESOURCE_POLICY,
    WAITING_FOR_HUMAN,
    COMPLETED,
    FAILED
  }

  public enum ToolId {
    NAVIGATE,
    GET_CURRENT_STATE,
    CLICK_TARGET,
    DOUBLE_CLICK_TARGET,
    RIGHT_CLICK_TARGET,
    HOVER_TARGET,
    CLEAR_TARGET,
    CHECK_TARGET,
    UNCHECK_TARGET,
    TYPE_TEXT,
    FILL,
    PASTE_AGENT_CLIPBOARD,
    SCROLL,
    WAIT_FOR,
    OPEN_TAB,
    SWITCH_TAB,
    CLOSE_TAB,
    ACCEPT_DIALOG,
    DISMISS_DIALOG,
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
    TOUCH_END,
    EXECUTE_ACTIONS,
    GET_URL,
    GET_PAGE_SUMMARY,
    REQUEST_HUMAN_TAKEOVER
  }

  public enum ExecutionStrategy {
    SEMANTIC_DOM,
    ACCESSIBILITY,
    DESKTOP_INPUT,
    VISION_DESKTOP,
    HUMAN_ASSIST,
    HUMAN_TAKEOVER
  }

  public enum ActionDataClass {
    PUBLIC,
    PII,
    CREDENTIAL,
    OTP
  }

  public enum WaitCondition {
    STATE_CHANGED,
    STATE_STABLE,
    TARGET_PRESENT
  }

  /**
   * Tool 的结构化输入。sealedPayload 只保存平台加密密文，API View 必须剔除；Target 与 Revision 用于执行前再次绑定权威 Browser State。
   */
  public record StepInput(
      String targetRef,
      Long targetRevision,
      String sealedPayload,
      String payloadHash,
      Integer payloadLength,
      ActionDataClass dataClass,
      Integer scrollDeltaY,
      WaitCondition waitCondition,
      Integer timeoutMs,
      boolean allowSensitiveTarget,
      int maximumAttempts,
      List<ActionInput> actions,
      boolean stopOnError,
      String tabId,
      String tabUrl,
      String dialogId,
      String endTargetRef,
      String endElementId,
      String key,
      Integer button,
      Integer deltaX,
      Integer deltaY,
      Integer durationMs) {
    public StepInput(
        String targetRef,
        Long targetRevision,
        String sealedPayload,
        String payloadHash,
        Integer payloadLength,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        boolean allowSensitiveTarget,
        int maximumAttempts) {
      this(
          targetRef,
          targetRevision,
          sealedPayload,
          payloadHash,
          payloadLength,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          allowSensitiveTarget,
          maximumAttempts,
          List.of(),
          true,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public StepInput(
        String targetRef,
        Long targetRevision,
        String sealedPayload,
        String payloadHash,
        Integer payloadLength,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        boolean allowSensitiveTarget,
        int maximumAttempts,
        List<ActionInput> actions,
        boolean stopOnError,
        String tabId,
        String tabUrl,
        String dialogId) {
      this(
          targetRef,
          targetRevision,
          sealedPayload,
          payloadHash,
          payloadLength,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          allowSensitiveTarget,
          maximumAttempts,
          actions,
          stopOnError,
          tabId,
          tabUrl,
          dialogId,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public StepInput(
        String targetRef,
        Long targetRevision,
        String sealedPayload,
        String payloadHash,
        Integer payloadLength,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        boolean allowSensitiveTarget,
        int maximumAttempts,
        List<ActionInput> actions,
        boolean stopOnError,
        String tabId,
        String tabUrl) {
      this(
          targetRef,
          targetRevision,
          sealedPayload,
          payloadHash,
          payloadLength,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          allowSensitiveTarget,
          maximumAttempts,
          actions,
          stopOnError,
          tabId,
          tabUrl,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public StepInput(
        String targetRef,
        Long targetRevision,
        String sealedPayload,
        String payloadHash,
        Integer payloadLength,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        boolean allowSensitiveTarget,
        int maximumAttempts,
        List<ActionInput> actions,
        boolean stopOnError) {
      this(
          targetRef,
          targetRevision,
          sealedPayload,
          payloadHash,
          payloadLength,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          allowSensitiveTarget,
          maximumAttempts,
          actions,
          stopOnError,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public StepInput {
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  /** One ordered primitive inside EXECUTE_ACTIONS. */
  public record ActionInput(
      String actionId,
      ToolId toolId,
      String targetRef,
      String elementId,
      Long targetRevision,
      String sealedPayload,
      String payloadHash,
      Integer payloadLength,
      ActionDataClass dataClass,
      Integer scrollDeltaY,
      WaitCondition waitCondition,
      Integer timeoutMs,
      boolean allowSensitiveTarget,
      int maximumAttempts,
      String tabId,
      String tabUrl,
      String dialogId,
      String endTargetRef,
      String endElementId,
      String key,
      Integer button,
      Integer deltaX,
      Integer deltaY,
      Integer durationMs) {
    public ActionInput(
        String actionId,
        ToolId toolId,
        String targetRef,
        String elementId,
        Long targetRevision,
        String sealedPayload,
        String payloadHash,
        Integer payloadLength,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        boolean allowSensitiveTarget,
        int maximumAttempts) {
      this(
          actionId,
          toolId,
          targetRef,
          elementId,
          targetRevision,
          sealedPayload,
          payloadHash,
          payloadLength,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          allowSensitiveTarget,
          maximumAttempts,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ActionInput(
        String actionId,
        ToolId toolId,
        String targetRef,
        String elementId,
        Long targetRevision,
        String sealedPayload,
        String payloadHash,
        Integer payloadLength,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        boolean allowSensitiveTarget,
        int maximumAttempts,
        String tabId,
        String tabUrl,
        String dialogId) {
      this(
          actionId,
          toolId,
          targetRef,
          elementId,
          targetRevision,
          sealedPayload,
          payloadHash,
          payloadLength,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          allowSensitiveTarget,
          maximumAttempts,
          tabId,
          tabUrl,
          dialogId,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ActionInput(
        String actionId,
        ToolId toolId,
        String targetRef,
        String elementId,
        Long targetRevision,
        String sealedPayload,
        String payloadHash,
        Integer payloadLength,
        ActionDataClass dataClass,
        Integer scrollDeltaY,
        WaitCondition waitCondition,
        Integer timeoutMs,
        boolean allowSensitiveTarget,
        int maximumAttempts,
        String tabId,
        String tabUrl) {
      this(
          actionId,
          toolId,
          targetRef,
          elementId,
          targetRevision,
          sealedPayload,
          payloadHash,
          payloadLength,
          dataClass,
          scrollDeltaY,
          waitCondition,
          timeoutMs,
          allowSensitiveTarget,
          maximumAttempts,
          tabId,
          tabUrl,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
  }

  public record InstructionSource(
      String sourceId,
      InstructionSourceType sourceType,
      TrustLevel trustLevel,
      String classification,
      String contentHash,
      boolean executableInstructionAllowed,
      List<String> taintLabels) {}

  public record SecurityEvent(
      String eventId,
      String eventType,
      String severity,
      String decision,
      String ruleCode,
      InstructionSourceType sourceType,
      String contentHash,
      Instant createdAt) {}

  /** capabilityToken 仅供内部 Executor / Tool Service 使用；API View 必须显式映射并剔除此字段。 */
  public record PlanStep(
      String stepId,
      ToolId toolId,
      RiskClass riskClass,
      String targetUrl,
      StepInput input,
      String rationale,
      List<String> supportingSources,
      TrustLevel trustFloor,
      List<String> taintLabels,
      boolean requiredConfirmation,
      ExecutionStrategy strategy,
      String requiredStateQuality,
      String verification,
      String capabilityTokenId,
      String capabilityToken) {}

  public record AgentPlan(
      String intentId, List<PlanStep> steps, int maxActions, int replanBudget, Instant expiresAt) {}

  public record ToolExecutionResult(
      String stepId,
      ToolId toolId,
      String status,
      String resultHash,
      java.util.Map<String, Object> output,
      String verification,
      Instant completedAt) {}

  public record IntentEvaluation(
      String sanitizedGoal,
      IntentDecision decision,
      RiskClass riskClass,
      String reason,
      List<InstructionSource> sources,
      List<SecurityEvent> securityEvents) {}
}
