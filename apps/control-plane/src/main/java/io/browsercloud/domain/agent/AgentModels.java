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
    TYPE_TEXT,
    SCROLL,
    WAIT_FOR,
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
    PII
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
      Integer timeoutMs) {}

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
