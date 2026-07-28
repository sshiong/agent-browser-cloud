package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable incremental migration of every Session route owned by one tenant. */
@Entity
@Table(name = "coordinator_route_migrations")
public class CoordinatorRouteMigrationEntity {

  @Id
  @Column(name = "migration_id")
  private String migrationId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "source_route_epoch", nullable = false)
  private long sourceRouteEpoch;

  @Column(name = "target_route_epoch", nullable = false)
  private long targetRouteEpoch;

  @Column(name = "source_virtual_partitions", nullable = false)
  private int sourceVirtualPartitions;

  @Column(name = "target_virtual_partitions", nullable = false)
  private int targetVirtualPartitions;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "total_sessions", nullable = false)
  private int totalSessions;

  @Column(name = "migrated_sessions", nullable = false)
  private int migratedSessions;

  @Column(name = "blocked_sessions", nullable = false)
  private int blockedSessions;

  @Column(name = "requested_by", nullable = false)
  private String requestedBy;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Column(name = "failure_code")
  private String failureCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected CoordinatorRouteMigrationEntity() {}

  public CoordinatorRouteMigrationEntity(
      String migrationId,
      String tenantId,
      long sourceRouteEpoch,
      long targetRouteEpoch,
      int sourceVirtualPartitions,
      int targetVirtualPartitions,
      String requestedBy,
      String requestId,
      Instant now) {
    this.migrationId = migrationId;
    this.tenantId = tenantId;
    this.sourceRouteEpoch = sourceRouteEpoch;
    this.targetRouteEpoch = targetRouteEpoch;
    this.sourceVirtualPartitions = sourceVirtualPartitions;
    this.targetVirtualPartitions = targetVirtualPartitions;
    this.state = "MIGRATING";
    this.requestedBy = requestedBy;
    this.requestId = requestId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void progress(int totalSessions, int migratedSessions, int blockedSessions, Instant now) {
    this.totalSessions = totalSessions;
    this.migratedSessions = migratedSessions;
    this.blockedSessions = blockedSessions;
    this.updatedAt = now;
  }

  public void committed(Instant now) {
    this.state = "COMMITTED";
    this.blockedSessions = 0;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public void failed(String failureCode, Instant now) {
    this.state = "FAILED";
    this.failureCode = failureCode;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public String getMigrationId() {
    return migrationId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public long getSourceRouteEpoch() {
    return sourceRouteEpoch;
  }

  public long getTargetRouteEpoch() {
    return targetRouteEpoch;
  }

  public int getSourceVirtualPartitions() {
    return sourceVirtualPartitions;
  }

  public int getTargetVirtualPartitions() {
    return targetVirtualPartitions;
  }

  public String getState() {
    return state;
  }

  public int getTotalSessions() {
    return totalSessions;
  }

  public int getMigratedSessions() {
    return migratedSessions;
  }

  public int getBlockedSessions() {
    return blockedSessions;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getFailureCode() {
    return failureCode;
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
}
