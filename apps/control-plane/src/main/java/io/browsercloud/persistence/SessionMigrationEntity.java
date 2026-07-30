package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "session_migrations")
public class SessionMigrationEntity {

  @Id
  @Column(name = "migration_id")
  private String migrationId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "source_node_id", nullable = false)
  private String sourceNodeId;

  @Column(name = "target_node_id")
  private String targetNodeId;

  @Column(name = "source_context_epoch", nullable = false)
  private long sourceContextEpoch;

  @Column(name = "target_context_epoch")
  private Long targetContextEpoch;

  @Column(name = "checkpoint_id")
  private String checkpointId;

  @Column(name = "hibernate_operation_id")
  private String hibernateOperationId;

  @Column(name = "restore_operation_id")
  private String restoreOperationId;

  @Column(name = "target_cleanup_operation_id")
  private String targetCleanupOperationId;

  @Column(name = "target_attempt", nullable = false)
  private int targetAttempt;

  @Column(name = "maximum_target_attempts", nullable = false)
  private int maximumTargetAttempts;

  @Column(name = "failed_target_node_ids", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String failedTargetNodeIds;

  @Column(name = "last_target_failure_reason")
  private String lastTargetFailureReason;

  @Column(name = "resync_request_id")
  private String resyncRequestId;

  @Column(nullable = false)
  private String phase;

  @Column(name = "recovery_result")
  private String recoveryResult;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "workflow_type", nullable = false)
  private String workflowType;

  @Column(name = "source_proxy_allocation_id")
  private String sourceProxyAllocationId;

  @Column(name = "source_proxy_binding_profile_id")
  private String sourceProxyBindingProfileId;

  @Column(name = "target_proxy_binding_profile_id")
  private String targetProxyBindingProfileId;

  @Column(name = "target_proxy_binding_version")
  private Long targetProxyBindingVersion;

  @Column(name = "requested_by")
  private String requestedBy;

  @Column(name = "request_reason")
  private String requestReason;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "request_id")
  private String requestId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version private long version;

  protected SessionMigrationEntity() {}

  public SessionMigrationEntity(
      String migrationId,
      String sessionId,
      String tenantId,
      String sourceNodeId,
      long sourceContextEpoch,
      Instant now) {
    this.migrationId = migrationId;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.sourceNodeId = sourceNodeId;
    this.sourceContextEpoch = sourceContextEpoch;
    this.workflowType = "NODE_MIGRATION";
    this.phase = "CHECKPOINTING";
    this.maximumTargetAttempts = 3;
    this.failedTargetNodeIds = "[]";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public static SessionMigrationEntity proxyRebind(
      String migrationId,
      String sessionId,
      String tenantId,
      String sourceNodeId,
      long sourceContextEpoch,
      String sourceProxyAllocationId,
      String sourceProxyBindingProfileId,
      String targetProxyBindingProfileId,
      long targetProxyBindingVersion,
      String requestedBy,
      String requestReason,
      String idempotencyKey,
      String requestId,
      Instant now) {
    var workflow =
        new SessionMigrationEntity(
            migrationId, sessionId, tenantId, sourceNodeId, sourceContextEpoch, now);
    workflow.workflowType = "PROXY_REBIND";
    workflow.sourceProxyAllocationId = sourceProxyAllocationId;
    workflow.sourceProxyBindingProfileId = sourceProxyBindingProfileId;
    workflow.targetProxyBindingProfileId = targetProxyBindingProfileId;
    workflow.targetProxyBindingVersion = targetProxyBindingVersion;
    workflow.requestedBy = requestedBy;
    workflow.requestReason = requestReason;
    workflow.idempotencyKey = idempotencyKey;
    workflow.requestId = requestId;
    return workflow;
  }

  public void hibernateDispatched(String operationId, Instant now) {
    hibernateOperationId = operationId;
    updatedAt = now;
  }

  public void targetPlaced(String nodeId, long contextEpoch, String checkpointId, Instant now) {
    targetNodeId = nodeId;
    targetContextEpoch = contextEpoch;
    this.checkpointId = checkpointId;
    targetAttempt++;
    phase = "RESTORING";
    updatedAt = now;
  }

  public void restoreDispatched(String operationId, Instant now) {
    restoreOperationId = operationId;
    updatedAt = now;
  }

  public void targetCleanupDispatched(String operationId, String reason, Instant now) {
    targetCleanupOperationId = operationId;
    lastTargetFailureReason = reason;
    phase = "TARGET_CLEANUP";
    updatedAt = now;
  }

  public void targetRetryReady(String failedNodeIdsJson, Instant now) {
    failedTargetNodeIds = failedNodeIdsJson;
    targetNodeId = null;
    targetContextEpoch = null;
    restoreOperationId = null;
    phase = "PLACING_TARGET";
    updatedAt = now;
  }

  public void targetCleanupCommitted(String failedNodeIdsJson, Instant now) {
    failedTargetNodeIds = failedNodeIdsJson;
    updatedAt = now;
  }

  public void stateResync(String requestId, Instant now) {
    resyncRequestId = requestId;
    phase = "STATE_RESYNC";
    updatedAt = now;
  }

  public void businessValidation(Instant now) {
    phase = "BUSINESS_VALIDATION";
    updatedAt = now;
  }

  public void businessRecoveryAction(Instant now) {
    phase = "BUSINESS_RECOVERY_ACTION";
    updatedAt = now;
  }

  public void complete(String result, boolean ready, Instant now) {
    recoveryResult = result;
    phase = ready ? "COMPLETED" : "DEGRADED";
    updatedAt = now;
    completedAt = now;
  }

  public void fail(String reason, Instant now) {
    failureReason = reason;
    phase = "FAILED";
    updatedAt = now;
    completedAt = now;
  }

  public String getMigrationId() {
    return migrationId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSourceNodeId() {
    return sourceNodeId;
  }

  public long getSourceContextEpoch() {
    return sourceContextEpoch;
  }

  public String getTargetNodeId() {
    return targetNodeId;
  }

  public Long getTargetContextEpoch() {
    return targetContextEpoch;
  }

  public String getCheckpointId() {
    return checkpointId;
  }

  public String getHibernateOperationId() {
    return hibernateOperationId;
  }

  public String getRestoreOperationId() {
    return restoreOperationId;
  }

  public String getTargetCleanupOperationId() {
    return targetCleanupOperationId;
  }

  public int getTargetAttempt() {
    return targetAttempt;
  }

  public int getMaximumTargetAttempts() {
    return maximumTargetAttempts;
  }

  public String getFailedTargetNodeIds() {
    return failedTargetNodeIds;
  }

  public String getLastTargetFailureReason() {
    return lastTargetFailureReason;
  }

  public String getResyncRequestId() {
    return resyncRequestId;
  }

  public String getPhase() {
    return phase;
  }

  public String getRecoveryResult() {
    return recoveryResult;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public String getWorkflowType() {
    return workflowType;
  }

  public boolean isProxyRebind() {
    return "PROXY_REBIND".equals(workflowType);
  }

  public String getSourceProxyAllocationId() {
    return sourceProxyAllocationId;
  }

  public String getSourceProxyBindingProfileId() {
    return sourceProxyBindingProfileId;
  }

  public String getTargetProxyBindingProfileId() {
    return targetProxyBindingProfileId;
  }

  public Long getTargetProxyBindingVersion() {
    return targetProxyBindingVersion;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public String getRequestReason() {
    return requestReason;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestId() {
    return requestId;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
