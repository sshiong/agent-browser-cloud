package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Profile 元数据与最新已提交检查点。 */
@Entity
@Table(name = "profiles")
public class ProfileEntity {

  @Id
  @Column(name = "profile_id")
  private String profileId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "storage_path", nullable = false)
  private String storagePath;

  @Column(name = "latest_checkpoint_id")
  private String latestCheckpointId;

  @Column(name = "latest_checkpoint_epoch")
  private Long latestCheckpointEpoch;

  @Column(name = "profile_write_epoch", nullable = false)
  private long profileWriteEpoch;

  @Column(name = "core_size_bytes", nullable = false)
  private long coreSizeBytes;

  @Column(name = "checkpoint_file_count", nullable = false)
  private long checkpointFileCount;

  @Column(name = "restore_status", nullable = false)
  private String restoreStatus;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "last_checkpoint_at")
  private Instant lastCheckpointAt;

  protected ProfileEntity() {}

  public ProfileEntity(
      String profileId,
      String tenantId,
      String name,
      String description,
      String storagePath,
      Instant now) {
    this.profileId = profileId;
    this.tenantId = tenantId;
    this.name = name;
    this.description = description;
    this.storagePath = storagePath;
    this.profileWriteEpoch = 0;
    this.coreSizeBytes = 0;
    this.checkpointFileCount = 0;
    this.restoreStatus = "EMPTY";
    this.state = "ACTIVE";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public String getProfileId() {
    return profileId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getLatestCheckpointId() {
    return latestCheckpointId;
  }

  public Long getLatestCheckpointEpoch() {
    return latestCheckpointEpoch;
  }

  public long getLatestCheckpointEpochOrZero() {
    return latestCheckpointEpoch == null ? 0 : latestCheckpointEpoch;
  }

  public long getProfileWriteEpoch() {
    return profileWriteEpoch;
  }

  public long getCoreSizeBytes() {
    return coreSizeBytes;
  }

  public long getCheckpointFileCount() {
    return checkpointFileCount;
  }

  public String getRestoreStatus() {
    return restoreStatus;
  }

  public String getState() {
    return state;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getLastCheckpointAt() {
    return lastCheckpointAt;
  }

  public void commitCheckpoint(
      String checkpointId,
      long checkpointEpoch,
      long writeEpoch,
      long sizeBytes,
      long fileCount,
      String restoredFromStatus,
      Instant now) {
    this.latestCheckpointId = checkpointId;
    this.latestCheckpointEpoch = checkpointEpoch;
    this.profileWriteEpoch = writeEpoch;
    this.coreSizeBytes = sizeBytes;
    this.checkpointFileCount = fileCount;
    this.restoreStatus = restoredFromStatus;
    this.updatedAt = now;
    this.lastCheckpointAt = now;
  }
}
