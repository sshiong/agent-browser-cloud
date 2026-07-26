package io.browsercloud.persistence;

import io.browsercloud.domain.workflow.WorkflowState;
import io.browsercloud.domain.workflow.WorkflowStateMachine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "durable_workflows")
public class DurableWorkflowEntity {

  @Id
  @Column(name = "workflow_id")
  private String workflowId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "operation_id", nullable = false)
  private String operationId;

  @Column(name = "workflow_type", nullable = false)
  private String workflowType;

  @Column(nullable = false)
  private int attempt;

  @Column(nullable = false)
  private int priority;

  @Column(nullable = false)
  private String state;

  private String phase;

  @Column(name = "worker_id")
  private String workerId;

  @Column(name = "coordinator_term", nullable = false)
  private long coordinatorTerm;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "operation_epoch", nullable = false)
  private long operationEpoch;

  @Column(name = "dispatched_at")
  private Instant dispatchedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "phase_deadline")
  private Instant phaseDeadline;

  @Column(name = "operation_deadline")
  private Instant operationDeadline;

  @Column(name = "cancellation_epoch", nullable = false)
  private long cancellationEpoch;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "external_receipt")
  private String externalReceipt;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "commit_marker")
  private String commitMarker;

  @Column(name = "compensation_action", nullable = false)
  private String compensationAction;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version private long version;

  public String getWorkflowId() {
    return workflowId;
  }

  public void setWorkflowId(String workflowId) {
    this.workflowId = workflowId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getOperationId() {
    return operationId;
  }

  public void setOperationId(String operationId) {
    this.operationId = operationId;
  }

  public void setWorkflowType(String workflowType) {
    this.workflowType = workflowType;
  }

  public String getWorkflowType() {
    return workflowType;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public int getPriority() {
    return priority;
  }

  public String getPhase() {
    return phase;
  }

  public void setPhase(String phase) {
    this.phase = phase;
  }

  public void setWorkerId(String workerId) {
    this.workerId = workerId;
  }

  public long getCoordinatorTerm() {
    return coordinatorTerm;
  }

  public void setCoordinatorTerm(long coordinatorTerm) {
    this.coordinatorTerm = coordinatorTerm;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public void setContextEpoch(long contextEpoch) {
    this.contextEpoch = contextEpoch;
  }

  public long getOperationEpoch() {
    return operationEpoch;
  }

  public void setOperationEpoch(long operationEpoch) {
    this.operationEpoch = operationEpoch;
  }

  public void setDispatchedAt(Instant dispatchedAt) {
    this.dispatchedAt = dispatchedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public void setHeartbeatAt(Instant heartbeatAt) {
    this.heartbeatAt = heartbeatAt;
  }

  public Instant getPhaseDeadline() {
    return phaseDeadline;
  }

  public void setPhaseDeadline(Instant phaseDeadline) {
    this.phaseDeadline = phaseDeadline;
  }

  public void setOperationDeadline(Instant operationDeadline) {
    this.operationDeadline = operationDeadline;
  }

  public void setCancellationEpoch(long cancellationEpoch) {
    this.cancellationEpoch = cancellationEpoch;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getExternalReceipt() {
    return externalReceipt;
  }

  public void setExternalReceipt(String externalReceipt) {
    this.externalReceipt = externalReceipt;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public String getCommitMarker() {
    return commitMarker;
  }

  public void setCommitMarker(String commitMarker) {
    this.commitMarker = commitMarker;
  }

  public String getCompensationAction() {
    return compensationAction;
  }

  public void setCompensationAction(String compensationAction) {
    this.compensationAction = compensationAction;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public void transition(WorkflowState target, Instant now) {
    var current = WorkflowState.valueOf(state);
    WorkflowStateMachine.assertTransitionAllowed(current, target);
    state = target.name();
    updatedAt = now;
    if (target == WorkflowState.COMPLETED
        || target == WorkflowState.COMPENSATED
        || target == WorkflowState.DEAD_LETTER) {
      completedAt = now;
    }
  }
}
