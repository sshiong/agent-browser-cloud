package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Durable metadata for a streamed, Storage-Helper-validated Profile checkpoint import. */
@Entity
@Table(name = "profile_import_jobs")
public class ProfileImportJobEntity {

  @Id
  @Column(name = "import_id")
  private String importId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "owner_actor_id", nullable = false)
  private String ownerActorId;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false)
  private String requestHash;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Column(name = "operation_id", nullable = false)
  private String operationId;

  @Column(name = "profile_id", nullable = false)
  private String profileId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(name = "profile_description")
  private String profileDescription;

  @Column(name = "runtime_build_id", nullable = false)
  private String runtimeBuildId;

  @Column(name = "archive_sha256", nullable = false)
  private String archiveSha256;

  @Column(name = "archive_size_bytes", nullable = false)
  private long archiveSizeBytes;

  @Column(nullable = false)
  private String state;

  @Column(name = "node_id")
  private String nodeId;

  @Column(name = "checkpoint_id", nullable = false)
  private String checkpointId;

  @Column(name = "checkpoint_epoch")
  private Long checkpointEpoch;

  @Column(name = "profile_write_epoch")
  private Long profileWriteEpoch;

  @Column(name = "core_size_bytes")
  private Long coreSizeBytes;

  @Column(name = "checkpoint_file_count")
  private Long checkpointFileCount;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected ProfileImportJobEntity() {}

  public ProfileImportJobEntity(
      String importId,
      String tenantId,
      String ownerActorId,
      String idempotencyKey,
      String requestHash,
      String requestId,
      String operationId,
      String profileId,
      String profileName,
      String profileDescription,
      String runtimeBuildId,
      String archiveSha256,
      long archiveSizeBytes,
      String checkpointId,
      String nodeId,
      Instant now) {
    this.importId = importId;
    this.tenantId = tenantId;
    this.ownerActorId = ownerActorId;
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
    this.requestId = requestId;
    this.operationId = operationId;
    this.profileId = profileId;
    this.profileName = profileName;
    this.profileDescription = profileDescription;
    this.runtimeBuildId = runtimeBuildId;
    this.archiveSha256 = archiveSha256;
    this.archiveSizeBytes = archiveSizeBytes;
    this.checkpointId = checkpointId;
    this.nodeId = nodeId;
    this.state = "REQUESTED";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void uploading(String selectedNodeId, Instant now) {
    state = "UPLOADING";
    nodeId = selectedNodeId;
    errorCode = null;
    completedAt = null;
    updatedAt = now;
  }

  public void validating(Instant now) {
    state = "VALIDATING";
    updatedAt = now;
  }

  public void committed(
      String committedNodeId,
      long committedCheckpointEpoch,
      long committedProfileWriteEpoch,
      long committedCoreSizeBytes,
      long committedCheckpointFileCount,
      Instant now) {
    state = "COMMITTED";
    nodeId = committedNodeId;
    checkpointEpoch = committedCheckpointEpoch;
    profileWriteEpoch = committedProfileWriteEpoch;
    coreSizeBytes = committedCoreSizeBytes;
    checkpointFileCount = committedCheckpointFileCount;
    errorCode = null;
    updatedAt = now;
    completedAt = now;
  }

  public void failed(String stableErrorCode, Instant now) {
    state = "FAILED";
    errorCode = stableErrorCode;
    updatedAt = now;
    completedAt = now;
  }

  public String getImportId() {
    return importId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getOwnerActorId() {
    return ownerActorId;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getOperationId() {
    return operationId;
  }

  public String getProfileId() {
    return profileId;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getProfileDescription() {
    return profileDescription;
  }

  public String getRuntimeBuildId() {
    return runtimeBuildId;
  }

  public String getArchiveSha256() {
    return archiveSha256;
  }

  public long getArchiveSizeBytes() {
    return archiveSizeBytes;
  }

  public String getState() {
    return state;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getCheckpointId() {
    return checkpointId;
  }

  public Long getCheckpointEpoch() {
    return checkpointEpoch;
  }

  public Long getProfileWriteEpoch() {
    return profileWriteEpoch;
  }

  public Long getCoreSizeBytes() {
    return coreSizeBytes;
  }

  public Long getCheckpointFileCount() {
    return checkpointFileCount;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public long getVersion() {
    return version;
  }
}
