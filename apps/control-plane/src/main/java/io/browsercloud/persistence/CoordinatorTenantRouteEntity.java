package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** PostgreSQL-authoritative tenant virtual-partition route. */
@Entity
@Table(name = "coordinator_tenant_routes")
public class CoordinatorTenantRouteEntity {

  @Id
  @Column(name = "tenant_id")
  private String tenantId;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "active_virtual_partitions", nullable = false)
  private int activeVirtualPartitions;

  @Column(name = "active_route_epoch", nullable = false)
  private long activeRouteEpoch;

  @Column(name = "pending_virtual_partitions")
  private Integer pendingVirtualPartitions;

  @Column(name = "pending_route_epoch")
  private Long pendingRouteEpoch;

  @Column(name = "active_migration_id")
  private String activeMigrationId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected CoordinatorTenantRouteEntity() {}

  public CoordinatorTenantRouteEntity(String tenantId, Instant now) {
    this.tenantId = tenantId;
    this.state = "STABLE";
    this.activeVirtualPartitions = 1;
    this.activeRouteEpoch = 1;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void beginMigration(
      String migrationId, int targetVirtualPartitions, long targetRouteEpoch, Instant now) {
    this.state = "MIGRATING";
    this.activeMigrationId = migrationId;
    this.pendingVirtualPartitions = targetVirtualPartitions;
    this.pendingRouteEpoch = targetRouteEpoch;
    this.updatedAt = now;
  }

  public void commitMigration(Instant now) {
    if (pendingVirtualPartitions == null || pendingRouteEpoch == null) {
      throw new IllegalStateException("Pending tenant route is unavailable");
    }
    this.activeVirtualPartitions = pendingVirtualPartitions;
    this.activeRouteEpoch = pendingRouteEpoch;
    this.state = "STABLE";
    this.activeMigrationId = null;
    this.pendingVirtualPartitions = null;
    this.pendingRouteEpoch = null;
    this.updatedAt = now;
  }

  public void failMigration(Instant now) {
    this.state = "STABLE";
    this.activeMigrationId = null;
    this.pendingVirtualPartitions = null;
    this.pendingRouteEpoch = null;
    this.updatedAt = now;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getState() {
    return state;
  }

  public int getActiveVirtualPartitions() {
    return activeVirtualPartitions;
  }

  public long getActiveRouteEpoch() {
    return activeRouteEpoch;
  }

  public Integer getPendingVirtualPartitions() {
    return pendingVirtualPartitions;
  }

  public Long getPendingRouteEpoch() {
    return pendingRouteEpoch;
  }

  public String getActiveMigrationId() {
    return activeMigrationId;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
