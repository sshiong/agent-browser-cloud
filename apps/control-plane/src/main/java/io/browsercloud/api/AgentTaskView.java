package io.browsercloud.api;

import io.browsercloud.api.AgentReviewerModels.AgentReviewView;
import io.browsercloud.domain.agent.AgentModels.ActionDataClass;
import io.browsercloud.domain.agent.AgentModels.ExecutionStrategy;
import io.browsercloud.domain.agent.AgentModels.InstructionSourceType;
import io.browsercloud.domain.agent.AgentModels.IntentDecision;
import io.browsercloud.domain.agent.AgentModels.RiskClass;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.agent.AgentModels.ToolId;
import io.browsercloud.domain.agent.AgentModels.TrustLevel;
import io.browsercloud.domain.agent.AgentModels.WaitCondition;
import io.browsercloud.domain.agent.AgentPolicy;
import java.time.Instant;
import java.util.List;

public record AgentTaskView(
    String taskId,
    String sessionId,
    String goal,
    TaskState state,
    RiskClass riskClass,
    IntentDecision intentDecision,
    String blockedReason,
    AgentPolicy agentPolicy,
    int currentStep,
    int totalSteps,
    int replanCount,
    StepExecutionView stepExecution,
    ConfirmationView confirmation,
    HumanHandoffView humanHandoff,
    AgentReviewView review,
    List<String> allowedDomains,
    PlanView plan,
    String operationId,
    List<ToolExecutionResultView> executionResults,
    String lastError,
    List<SecurityEventView> securityEvents,
    Instant createdAt,
    Instant updatedAt) {

  public record StepExecutionView(
      String pendingStepId,
      ToolId pendingToolId,
      Long baseStateVersion,
      String baseContentHash,
      Instant deadline,
      Instant leaseUntil,
      String replanReason) {}

  public record ConfirmationView(
      String confirmationId,
      String status,
      Instant expiresAt,
      Instant decidedAt,
      String actorId,
      String evidenceHash) {}

  public record HumanHandoffView(
      String requestId, String status, Instant expiresAt, String actorId) {}

  public record PlanView(
      String intentId,
      List<PlanStepView> steps,
      int maxActions,
      int replanBudget,
      Instant expiresAt) {}

  public record PlanStepView(
      String stepId,
      ToolId toolId,
      RiskClass riskClass,
      String targetUrl,
      StepInputView input,
      String rationale,
      List<String> supportingSources,
      TrustLevel trustFloor,
      List<String> taintLabels,
      boolean requiredConfirmation,
      ExecutionStrategy strategy,
      String requiredStateQuality,
      String verification,
      String capabilityTokenId) {}

  public record StepInputView(
      String targetRef,
      Long targetRevision,
      String payloadHash,
      Integer payloadLength,
      ActionDataClass dataClass,
      Integer scrollDeltaY,
      WaitCondition waitCondition,
      Integer timeoutMs) {}

  public record SecurityEventView(
      String eventId,
      String eventType,
      String severity,
      String decision,
      String ruleCode,
      InstructionSourceType sourceType,
      String contentHash,
      Instant createdAt) {}

  public record ToolExecutionResultView(
      String stepId,
      ToolId toolId,
      String status,
      String resultHash,
      java.util.Map<String, Object> output,
      String verification,
      Instant completedAt) {}
}
