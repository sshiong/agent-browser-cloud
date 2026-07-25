package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentModels.ExecutionStrategy;
import io.browsercloud.domain.agent.AgentModels.InstructionSourceType;
import io.browsercloud.domain.agent.AgentModels.IntentDecision;
import io.browsercloud.domain.agent.AgentModels.RiskClass;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.agent.AgentModels.ToolId;
import io.browsercloud.domain.agent.AgentModels.TrustLevel;
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
    int currentStep,
    int totalSteps,
    List<String> allowedDomains,
    PlanView plan,
    List<SecurityEventView> securityEvents,
    Instant createdAt,
    Instant updatedAt) {

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
      String rationale,
      List<String> supportingSources,
      TrustLevel trustFloor,
      List<String> taintLabels,
      boolean requiredConfirmation,
      ExecutionStrategy strategy,
      String requiredStateQuality,
      String verification,
      String capabilityTokenId) {}

  public record SecurityEventView(
      String eventId,
      String eventType,
      String severity,
      String decision,
      String ruleCode,
      InstructionSourceType sourceType,
      String contentHash,
      Instant createdAt) {}
}
