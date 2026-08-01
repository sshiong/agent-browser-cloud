package io.browsercloud.persistence;

import io.browsercloud.api.WorkspaceMetadataBatchOperationModels.WorkspaceMetadataBatchAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workspace_metadata_batch_operations")
public class WorkspaceMetadataBatchOperationEntity {

  @Id
  @Column(name = "batch_operation_id")
  private String batchOperationId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "actor_id", nullable = false)
  private String actorId;

  @Column(nullable = false)
  private String action;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String selector;

  @Column(name = "target_group_id")
  private String targetGroupId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "target_tag_ids", nullable = false, columnDefinition = "jsonb")
  private List<String> targetTagIds;

  @Column(nullable = false)
  private String reason;

  @Column(name = "request_hash", nullable = false)
  private String requestHash;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "cancellation_requested_at")
  private Instant cancellationRequestedAt;

  @Column(name = "cancellation_request_hash")
  private String cancellationRequestHash;

  @Column(name = "cancellation_idempotency_key")
  private String cancellationIdempotencyKey;

  @Column(name = "deadline_at", nullable = false)
  private Instant deadlineAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkspaceMetadataBatchOperationEntity() {}

  public WorkspaceMetadataBatchOperationEntity(
      String batchOperationId,
      String tenantId,
      String actorId,
      WorkspaceMetadataBatchAction action,
      String selector,
      String targetGroupId,
      List<String> targetTagIds,
      String reason,
      String requestHash,
      String idempotencyKey,
      Instant deadlineAt,
      Instant now) {
    this.batchOperationId = batchOperationId;
    this.tenantId = tenantId;
    this.actorId = actorId;
    this.action = action.name();
    this.selector = selector;
    this.targetGroupId = targetGroupId;
    this.targetTagIds = List.copyOf(targetTagIds);
    this.reason = reason;
    this.requestHash = requestHash;
    this.idempotencyKey = idempotencyKey;
    this.deadlineAt = deadlineAt;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void requestCancellation(Instant now, String requestHash, String idempotencyKey) {
    if (cancellationRequestedAt == null) {
      cancellationRequestedAt = now;
      cancellationRequestHash = requestHash;
      cancellationIdempotencyKey = idempotencyKey;
      updatedAt = now;
    }
  }

  public String getBatchOperationId() {
    return batchOperationId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getActorId() {
    return actorId;
  }

  public WorkspaceMetadataBatchAction getAction() {
    return WorkspaceMetadataBatchAction.valueOf(action);
  }

  public String getSelector() {
    return selector;
  }

  public String getTargetGroupId() {
    return targetGroupId;
  }

  public List<String> getTargetTagIds() {
    return List.copyOf(targetTagIds);
  }

  public String getReason() {
    return reason;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public Instant getCancellationRequestedAt() {
    return cancellationRequestedAt;
  }

  public String getCancellationRequestHash() {
    return cancellationRequestHash;
  }

  public String getCancellationIdempotencyKey() {
    return cancellationIdempotencyKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
