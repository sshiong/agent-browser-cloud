package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Exclusive Operation JPA 实体。 */
@Entity
@Table(name = "exclusive_operations")
public class ExclusiveOperationEntity {

  @Id
  @Column(name = "operation_id")
  private String operationId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "owner_type", nullable = false)
  private String ownerType;

  @Column(name = "actor_id")
  private String actorId;

  @Column(name = "mode", nullable = false)
  private String mode;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "operation_epoch", nullable = false)
  private long operationEpoch;

  @Column(name = "coordinator_term", nullable = false)
  private long coordinatorTerm;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "workflow_id")
  private String workflowId;

  @Column(name = "cancellable", nullable = false)
  private boolean cancellable;

  @Column(name = "preemptible", nullable = false)
  private boolean preemptible;

  @Column(name = "phase", nullable = false)
  private String phase;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "deadline", nullable = false)
  private Instant deadline;

  @Column(name = "allowed_capabilities", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String allowedCapabilities;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  public ExclusiveOperationEntity() {}

  // Getters and Setters
  public String getOperationId() {
    return operationId;
  }

  public void setOperationId(String operationId) {
    this.operationId = operationId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getOwnerType() {
    return ownerType;
  }

  public void setOwnerType(String ownerType) {
    this.ownerType = ownerType;
  }

  public String getActorId() {
    return actorId;
  }

  public void setActorId(String actorId) {
    this.actorId = actorId;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public long getOperationEpoch() {
    return operationEpoch;
  }

  public void setOperationEpoch(long operationEpoch) {
    this.operationEpoch = operationEpoch;
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

  public String getWorkflowId() {
    return workflowId;
  }

  public void setWorkflowId(String workflowId) {
    this.workflowId = workflowId;
  }

  public boolean isCancellable() {
    return cancellable;
  }

  public void setCancellable(boolean cancellable) {
    this.cancellable = cancellable;
  }

  public boolean isPreemptible() {
    return preemptible;
  }

  public void setPreemptible(boolean preemptible) {
    this.preemptible = preemptible;
  }

  public String getPhase() {
    return phase;
  }

  public void setPhase(String phase) {
    this.phase = phase;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public Instant getDeadline() {
    return deadline;
  }

  public void setDeadline(Instant deadline) {
    this.deadline = deadline;
  }

  public String getAllowedCapabilities() {
    return allowedCapabilities;
  }

  public void setAllowedCapabilities(String allowedCapabilities) {
    this.allowedCapabilities = allowedCapabilities;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }
}
