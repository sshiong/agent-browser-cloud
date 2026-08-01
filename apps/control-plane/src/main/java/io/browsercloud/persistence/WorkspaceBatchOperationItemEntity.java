package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "workspace_batch_operation_items")
public class WorkspaceBatchOperationItemEntity {

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

  @Column(name = "command_id", nullable = false)
  private String commandId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WorkspaceBatchOperationItemEntity() {}

  public WorkspaceBatchOperationItemEntity(
      String batchItemId,
      String batchOperationId,
      String tenantId,
      String sessionId,
      int ordinal,
      String commandId,
      Instant createdAt) {
    this.batchItemId = batchItemId;
    this.batchOperationId = batchOperationId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.ordinal = ordinal;
    this.commandId = commandId;
    this.createdAt = createdAt;
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

  public String getCommandId() {
    return commandId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
