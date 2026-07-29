package io.browsercloud.persistence;

import static io.browsercloud.api.EnvironmentImportModels.EnvironmentImportState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "environment_import_jobs")
public class EnvironmentImportJobEntity {

  @Id
  @Column(name = "import_id")
  private String importId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "owner_actor_id", nullable = false)
  private String ownerActorId;

  @Column(nullable = false)
  private String name;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion;

  @Column(name = "manifest_hash", nullable = false)
  private String manifestHash;

  @Column(nullable = false)
  private String state;

  @Column(name = "total_count", nullable = false)
  private int totalCount;

  @Column(name = "ready_count", nullable = false)
  private int readyCount;

  @Column(name = "succeeded_count", nullable = false)
  private int succeededCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "committed_at")
  private Instant committedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected EnvironmentImportJobEntity() {}

  public EnvironmentImportJobEntity(
      String importId,
      String tenantId,
      String ownerActorId,
      String name,
      int schemaVersion,
      String manifestHash,
      EnvironmentImportState state,
      int totalCount,
      int readyCount,
      Instant now) {
    this.importId = importId;
    this.tenantId = tenantId;
    this.ownerActorId = ownerActorId;
    this.name = name.strip();
    this.schemaVersion = schemaVersion;
    this.manifestHash = manifestHash;
    this.state = state.name();
    this.totalCount = totalCount;
    this.readyCount = readyCount;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void start(Instant now) {
    this.state = EnvironmentImportState.EXECUTING.name();
    this.updatedAt = now;
  }

  public void commit(int succeededCount, Instant now) {
    this.state = EnvironmentImportState.COMMITTED.name();
    this.succeededCount = succeededCount;
    this.committedAt = now;
    this.updatedAt = now;
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

  public String getName() {
    return name;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public String getManifestHash() {
    return manifestHash;
  }

  public EnvironmentImportState getState() {
    return EnvironmentImportState.valueOf(state);
  }

  public int getTotalCount() {
    return totalCount;
  }

  public int getReadyCount() {
    return readyCount;
  }

  public int getSucceededCount() {
    return succeededCount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCommittedAt() {
    return committedAt;
  }

  public long getVersion() {
    return version;
  }
}
