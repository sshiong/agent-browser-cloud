package io.browsercloud.persistence;

import io.browsercloud.api.WorkspaceBatchOperationModels.WorkspaceBatchItemState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "workspace_metadata_batch_operation_items")
public class WorkspaceMetadataBatchOperationItemEntity {

  @Id
  @Column(name = "batch_item_id")
  private String batchItemId;

  @Column(name = "batch_operation_id", nullable = false)
  private String batchOperationId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(nullable = false)
  private int ordinal;

  @Column(nullable = false)
  private String state;

  @Column(name = "failure_code")
  private String failureCode;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected WorkspaceMetadataBatchOperationItemEntity() {}

  public WorkspaceMetadataBatchOperationItemEntity(
      String batchItemId,
      String batchOperationId,
      String tenantId,
      String sessionId,
      int ordinal,
      Instant now) {
    this.batchItemId = batchItemId;
    this.batchOperationId = batchOperationId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.ordinal = ordinal;
    this.state = WorkspaceBatchItemState.ACCEPTED.name();
    this.attempt = 0;
    this.nextAttemptAt = now;
    this.createdAt = now;
  }

  public String getBatchItemId() {
    return batchItemId;
  }

  public String getBatchOperationId() {
    return batchOperationId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public int getOrdinal() {
    return ordinal;
  }

  public WorkspaceBatchItemState getState() {
    return WorkspaceBatchItemState.valueOf(state);
  }

  public String getFailureCode() {
    return failureCode;
  }

  public int getAttempt() {
    return attempt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
