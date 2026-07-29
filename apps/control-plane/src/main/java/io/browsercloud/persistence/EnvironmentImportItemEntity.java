package io.browsercloud.persistence;

import static io.browsercloud.api.EnvironmentImportModels.EnvironmentImportExecutionState;
import static io.browsercloud.api.EnvironmentImportModels.EnvironmentImportValidationState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "environment_import_items")
public class EnvironmentImportItemEntity {

  @Id
  @Column(name = "item_id")
  private String itemId;

  @Column(name = "import_id", nullable = false)
  private String importId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "item_index", nullable = false)
  private int itemIndex;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> requestPayload;

  @Column(name = "request_hash", nullable = false)
  private String requestHash;

  @Column(name = "validation_state", nullable = false)
  private String validationState;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "validation_errors", nullable = false, columnDefinition = "jsonb")
  private List<String> validationErrors;

  @Column(name = "execution_state", nullable = false)
  private String executionState;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "operation_id")
  private String operationId;

  @Column(name = "request_id")
  private String requestId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected EnvironmentImportItemEntity() {}

  public EnvironmentImportItemEntity(
      String itemId,
      String importId,
      String tenantId,
      int itemIndex,
      String displayName,
      Map<String, Object> requestPayload,
      String requestHash,
      List<String> validationErrors,
      Instant now) {
    this.itemId = itemId;
    this.importId = importId;
    this.tenantId = tenantId;
    this.itemIndex = itemIndex;
    this.displayName = displayName.strip();
    this.requestPayload = new LinkedHashMap<>(requestPayload);
    this.requestHash = requestHash;
    this.validationErrors = List.copyOf(validationErrors);
    this.validationState =
        (validationErrors.isEmpty()
                ? EnvironmentImportValidationState.READY
                : EnvironmentImportValidationState.INVALID)
            .name();
    this.executionState = EnvironmentImportExecutionState.PENDING.name();
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void succeed(String sessionId, String operationId, String requestId, Instant now) {
    this.executionState = EnvironmentImportExecutionState.SUCCEEDED.name();
    this.sessionId = sessionId;
    this.operationId = operationId;
    this.requestId = requestId;
    this.updatedAt = now;
  }

  public String getItemId() {
    return itemId;
  }

  public String getImportId() {
    return importId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public int getItemIndex() {
    return itemIndex;
  }

  public String getDisplayName() {
    return displayName;
  }

  public Map<String, Object> getRequestPayload() {
    return requestPayload;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public EnvironmentImportValidationState getValidationState() {
    return EnvironmentImportValidationState.valueOf(validationState);
  }

  public List<String> getValidationErrors() {
    return validationErrors;
  }

  public EnvironmentImportExecutionState getExecutionState() {
    return EnvironmentImportExecutionState.valueOf(executionState);
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getOperationId() {
    return operationId;
  }

  public String getRequestId() {
    return requestId;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
