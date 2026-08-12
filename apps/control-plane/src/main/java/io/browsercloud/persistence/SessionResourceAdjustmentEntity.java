package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** PostgreSQL-authoritative lifecycle of one asynchronous Browser Node resource adjustment. */
@Entity
@Table(name = "session_resource_adjustments")
public class SessionResourceAdjustmentEntity {
  @Id private String operationId;

  @Column(nullable = false)
  private String sessionId;

  @Column(nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String state;

  @Column(nullable = false)
  private String reason;

  private String failureCode;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String oldResources;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String requestedResources;

  @Column(nullable = false)
  private Instant requestedAt;

  private Instant executingAt;
  private Instant acknowledgedAt;
  private Instant completedAt;
  private Instant reconciledAt;

  private String reconciliationOperationId;

  @Column(nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected SessionResourceAdjustmentEntity() {}

  public static SessionResourceAdjustmentEntity requested(
      String operationId,
      String sessionId,
      String tenantId,
      String reason,
      String oldResources,
      String requestedResources,
      Instant now) {
    var entity = new SessionResourceAdjustmentEntity();
    entity.operationId = operationId;
    entity.sessionId = sessionId;
    entity.tenantId = tenantId;
    entity.state = "REQUESTED";
    entity.reason = reason;
    entity.oldResources = oldResources;
    entity.requestedResources = requestedResources;
    entity.requestedAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public boolean markExecuting(Instant now) {
    if (!"REQUESTED".equals(state)) return false;
    state = "EXECUTING";
    executingAt = now;
    updatedAt = now;
    return true;
  }

  public boolean acknowledge(Instant now) {
    if ("ACKNOWLEDGED".equals(state) || "COMMITTED".equals(state)) return false;
    if (!"REQUESTED".equals(state) && !"EXECUTING".equals(state)) {
      throw new IllegalStateException("RESOURCE_ADJUSTMENT_NOT_ACKNOWLEDGEABLE:" + state);
    }
    if (executingAt == null) executingAt = now;
    state = "ACKNOWLEDGED";
    acknowledgedAt = now;
    updatedAt = now;
    return true;
  }

  public boolean commit(Instant now) {
    if ("COMMITTED".equals(state)) return false;
    if (!"ACKNOWLEDGED".equals(state)) {
      throw new IllegalStateException("RESOURCE_ADJUSTMENT_NOT_COMMITTABLE:" + state);
    }
    state = "COMMITTED";
    completedAt = now;
    updatedAt = now;
    return true;
  }

  public boolean fail(String errorCode, Instant now) {
    if ("COMMITTED".equals(state) || "FAILED".equals(state) || "RECONCILED".equals(state)) {
      return false;
    }
    state = "FAILED";
    failureCode = requireText(errorCode, "failure code");
    completedAt = now;
    updatedAt = now;
    return true;
  }

  /**
   * Records a compensating authority reconciliation after a timed-out Node ACK arrives late.
   *
   * <p>The original generic Operation remains TIMED_OUT. Its failure code is retained as history,
   * while {@code reconciliationOperationId} points to the new committed reconciliation Operation.
   */
  public boolean reconcile(String reconciliationOperationId, Instant now) {
    if ("RECONCILED".equals(state)) return false;
    if (!"FAILED".equals(state) || !"NODE_ACK_TIMEOUT".equals(failureCode)) {
      throw new IllegalStateException("RESOURCE_ADJUSTMENT_NOT_RECONCILABLE:" + state);
    }
    this.state = "RECONCILED";
    this.reconciliationOperationId =
        requireText(reconciliationOperationId, "reconciliation operation ID");
    this.acknowledgedAt = now;
    this.reconciledAt = now;
    this.updatedAt = now;
    return true;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(label + " is required");
    return value;
  }

  public String getOperationId() {
    return operationId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getState() {
    return state;
  }

  public String getReason() {
    return reason;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public String getOldResources() {
    return oldResources;
  }

  public String getRequestedResources() {
    return requestedResources;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getExecutingAt() {
    return executingAt;
  }

  public Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getReconciledAt() {
    return reconciledAt;
  }

  public String getReconciliationOperationId() {
    return reconciliationOperationId;
  }
}
