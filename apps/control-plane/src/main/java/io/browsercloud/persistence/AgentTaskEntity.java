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

  public void startExecution(String operationId, Instant now) {
    this.operationId = operationId;
    this.state = "RUNNING";
    this.executionStartedAt = now;
    this.updatedAt = now;
    this.lastError = null;
  }

  public void markNavigationPending(long baseStateVersion, Instant now) {
    this.pendingStateVersion = baseStateVersion;
    this.updatedAt = now;
  }

  public void recordReplan(Instant now) {
    this.replanCount += 1;
    this.updatedAt = now;
  }

  public void completeExecution(int completedSteps, String results, Instant now) {
    this.state = "COMPLETED";
    this.currentStep = completedSteps;
    this.executionResults = results;
    this.pendingStateVersion = null;
    this.executionCompletedAt = now;
    this.updatedAt = now;
  }

  public void failExecution(int completedSteps, String results, String error, Instant now) {
    this.state = "FAILED";
    this.currentStep = completedSteps;
    this.executionResults = results;
    this.pendingStateVersion = null;
    this.lastError = error;
    this.executionCompletedAt = now;
    this.updatedAt = now;
  }
}
