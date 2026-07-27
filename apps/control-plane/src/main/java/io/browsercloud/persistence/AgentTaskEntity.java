package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_tasks")
public class AgentTaskEntity {

  @Id
  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "goal", nullable = false)
  private String goal;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "risk_class", nullable = false)
  private String riskClass;

  @Column(name = "intent_decision", nullable = false)
  private String intentDecision;

  @Column(name = "blocked_reason")
  private String blockedReason;

  @Column(name = "current_step", nullable = false)
  private int currentStep;

  @Column(name = "replan_count", nullable = false)
  private int replanCount;

  @Column(name = "pending_state_version")
  private Long pendingStateVersion;

  @Column(name = "pending_step_id")
  private String pendingStepId;

  @Column(name = "pending_tool_id")
  private String pendingToolId;

  @Column(name = "pending_content_hash")
  private String pendingContentHash;

  @Column(name = "step_deadline_at")
  private Instant stepDeadlineAt;

  @Column(name = "executor_lease_owner")
  private String executorLeaseOwner;

  @Column(name = "executor_lease_until")
  private Instant executorLeaseUntil;

  @Column(name = "replan_reason")
  private String replanReason;

  @Column(name = "confirmation_id")
  private String confirmationId;

  @Column(name = "confirmation_status")
  private String confirmationStatus;

  @Column(name = "confirmation_expires_at")
  private Instant confirmationExpiresAt;

  @Column(name = "confirmation_decided_at")
  private Instant confirmationDecidedAt;

  @Column(name = "confirmation_actor_id")
  private String confirmationActorId;

  @Column(name = "confirmation_evidence_hash")
  private String confirmationEvidenceHash;

  @Column(name = "handoff_request_id")
  private String handoffRequestId;

  @Column(name = "handoff_status")
  private String handoffStatus;

  @Column(name = "handoff_expires_at")
  private Instant handoffExpiresAt;

  @Column(name = "handoff_actor_id")
  private String handoffActorId;

  @Column(name = "allowed_domains", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String allowedDomains;

  @Column(name = "plan", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String plan;

  @Column(name = "security_events", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String securityEvents;

  @Column(name = "operation_id")
  private String operationId;

  @Column(name = "execution_results", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String executionResults;

  @Column(name = "last_error")
  private String lastError;

  @Column(name = "execution_started_at")
  private Instant executionStartedAt;

  @Column(name = "execution_completed_at")
  private Instant executionCompletedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AgentTaskEntity() {}

  public AgentTaskEntity(
      String taskId,
      String tenantId,
      String sessionId,
      String goal,
      String state,
      String riskClass,
      String intentDecision,
      String blockedReason,
      String allowedDomains,
      String plan,
      String securityEvents,
      Instant now) {
    this.taskId = taskId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.goal = goal;
    this.state = state;
    this.riskClass = riskClass;
    this.intentDecision = intentDecision;
    this.blockedReason = blockedReason;
    this.currentStep = 0;
    this.replanCount = 0;
    this.allowedDomains = allowedDomains;
    this.plan = plan;
    this.securityEvents = securityEvents;
    this.executionResults = "[]";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getGoal() {
    return goal;
  }

  public String getState() {
    return state;
  }

  public String getRiskClass() {
    return riskClass;
  }

  public String getIntentDecision() {
    return intentDecision;
  }

  public String getBlockedReason() {
    return blockedReason;
  }

  public int getCurrentStep() {
    return currentStep;
  }

  public int getReplanCount() {
    return replanCount;
  }

  public Long getPendingStateVersion() {
    return pendingStateVersion;
  }

  public String getPendingStepId() {
    return pendingStepId;
  }

  public String getPendingToolId() {
    return pendingToolId;
  }

  public String getPendingContentHash() {
    return pendingContentHash;
  }

  public Instant getStepDeadlineAt() {
    return stepDeadlineAt;
  }

  public String getExecutorLeaseOwner() {
    return executorLeaseOwner;
  }

  public Instant getExecutorLeaseUntil() {
    return executorLeaseUntil;
  }

  public String getReplanReason() {
    return replanReason;
  }

  public String getConfirmationId() {
    return confirmationId;
  }

  public String getConfirmationStatus() {
    return confirmationStatus;
  }

  public Instant getConfirmationExpiresAt() {
    return confirmationExpiresAt;
  }

  public Instant getConfirmationDecidedAt() {
    return confirmationDecidedAt;
  }

  public String getConfirmationActorId() {
    return confirmationActorId;
  }

  public String getConfirmationEvidenceHash() {
    return confirmationEvidenceHash;
  }

  public String getHandoffRequestId() {
    return handoffRequestId;
  }

  public String getHandoffStatus() {
    return handoffStatus;
  }

  public Instant getHandoffExpiresAt() {
    return handoffExpiresAt;
  }

  public String getHandoffActorId() {
    return handoffActorId;
  }

  public String getAllowedDomains() {
    return allowedDomains;
  }

  public String getPlan() {
    return plan;
  }

  public String getSecurityEvents() {
    return securityEvents;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getOperationId() {
    return operationId;
  }

  public String getExecutionResults() {
    return executionResults;
  }

  public String getLastError() {
    return lastError;
  }

  public void startExecution(
      String operationId, String leaseOwner, Instant leaseUntil, Instant now) {
    this.operationId = operationId;
    this.state = "RUNNING";
    this.executorLeaseOwner = leaseOwner;
    this.executorLeaseUntil = leaseUntil;
    this.executionStartedAt = now;
    this.updatedAt = now;
    this.lastError = null;
  }

  public void markAsyncPending(
      int stepIndex,
      String stepId,
      String toolId,
      long baseStateVersion,
      String baseContentHash,
      Instant deadline,
      String results,
      String leaseOwner,
      Instant leaseUntil,
      Instant now) {
    this.currentStep = stepIndex;
    this.pendingStepId = stepId;
    this.pendingToolId = toolId;
    this.pendingStateVersion = baseStateVersion;
    this.pendingContentHash = baseContentHash;
    this.stepDeadlineAt = deadline;
    this.executionResults = results;
    this.executorLeaseOwner = leaseOwner;
    this.executorLeaseUntil = leaseUntil;
    this.updatedAt = now;
  }

  public void checkpoint(
      int completedSteps, String results, String leaseOwner, Instant leaseUntil, Instant now) {
    this.currentStep = completedSteps;
    this.executionResults = results;
    clearPendingStep();
    this.executorLeaseOwner = leaseOwner;
    this.executorLeaseUntil = leaseUntil;
    this.updatedAt = now;
  }

  public void recordReplan(String reason, Instant now) {
    this.replanCount += 1;
    this.replanReason = reason;
    this.updatedAt = now;
  }

  public void completeExecution(int completedSteps, String results, Instant now) {
    this.state = "COMPLETED";
    this.currentStep = completedSteps;
    this.executionResults = results;
    clearPendingStep();
    clearLease();
    this.executionCompletedAt = now;
    this.updatedAt = now;
  }

  public void failExecution(int completedSteps, String results, String error, Instant now) {
    this.state = "FAILED";
    this.currentStep = completedSteps;
    this.executionResults = results;
    clearPendingStep();
    clearLease();
    this.lastError = error;
    this.executionCompletedAt = now;
    this.updatedAt = now;
  }

  public void renewLease(String leaseOwner, Instant leaseUntil, Instant now) {
    this.executorLeaseOwner = leaseOwner;
    this.executorLeaseUntil = leaseUntil;
    this.updatedAt = now;
  }

  public void pauseByResourcePolicy(Instant now) {
    if (!"RUNNING".equals(state)) return;
    state = "PAUSED_BY_RESOURCE_POLICY";
    blockedReason = "RESOURCE_POLICY_MAXIMUM_REACHED";
    clearPendingStep();
    clearLease();
    updatedAt = now;
  }

  public void resumeAfterResourceRecovery(Instant now) {
    if (!"PAUSED_BY_RESOURCE_POLICY".equals(state)) return;
    state = "PLANNED";
    blockedReason = null;
    lastError = null;
    updatedAt = now;
  }

  public void awaitConfirmation(String id, Instant expiresAt, Instant now) {
    this.state = "AWAITING_CONFIRMATION";
    this.confirmationId = id;
    this.confirmationStatus = "PENDING";
    this.confirmationExpiresAt = expiresAt;
    this.updatedAt = now;
  }

  public void approveConfirmation(String actorId, String evidenceHash, Instant now) {
    this.state = "PLANNED";
    this.confirmationStatus = "APPROVED";
    this.confirmationActorId = actorId;
    this.confirmationEvidenceHash = evidenceHash;
    this.confirmationDecidedAt = now;
    this.updatedAt = now;
  }

  public void rejectConfirmation(String actorId, String evidenceHash, Instant now) {
    this.state = "BLOCKED";
    this.confirmationStatus = "REJECTED";
    this.confirmationActorId = actorId;
    this.confirmationEvidenceHash = evidenceHash;
    this.confirmationDecidedAt = now;
    this.lastError = "HUMAN_CONFIRMATION_REJECTED";
    this.updatedAt = now;
  }

  public void expireConfirmation(Instant now) {
    this.state = "BLOCKED";
    this.confirmationStatus = "EXPIRED";
    this.lastError = "HUMAN_CONFIRMATION_EXPIRED";
    this.updatedAt = now;
  }

  public void awaitHumanHandoff(
      int completedSteps, String results, String requestId, Instant expiresAt, Instant now) {
    this.state = "WAITING_FOR_HUMAN";
    this.currentStep = completedSteps;
    this.executionResults = results;
    this.handoffRequestId = requestId;
    this.handoffStatus = "PENDING";
    this.handoffExpiresAt = expiresAt;
    clearPendingStep();
    clearLease();
    this.updatedAt = now;
  }

  public void acceptHumanHandoff(String actorId, String results, Instant now) {
    this.handoffStatus = "ACCEPTED";
    this.handoffActorId = actorId;
    completeExecution(this.currentStep, results, now);
  }

  public void rejectHumanHandoff(String actorId, Instant now) {
    this.handoffStatus = "REJECTED";
    this.handoffActorId = actorId;
    failExecution(this.currentStep, this.executionResults, "HUMAN_HANDOFF_REJECTED", now);
  }

  public void expireHumanHandoff(Instant now) {
    this.handoffStatus = "EXPIRED";
    failExecution(this.currentStep, this.executionResults, "HUMAN_HANDOFF_EXPIRED", now);
  }

  private void clearPendingStep() {
    this.pendingStepId = null;
    this.pendingToolId = null;
    this.pendingStateVersion = null;
    this.pendingContentHash = null;
    this.stepDeadlineAt = null;
  }

  private void clearLease() {
    this.executorLeaseOwner = null;
    this.executorLeaseUntil = null;
  }
}
