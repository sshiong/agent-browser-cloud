package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Current virtual-partition and shard binding for one Session. */
@Entity
@Table(name = "coordinator_session_routes")
public class CoordinatorSessionRouteEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "route_epoch", nullable = false)
  private long routeEpoch;

  @Column(name = "virtual_partition", nullable = false)
  private int virtualPartition;

  @Column(name = "shard_id", nullable = false)
  private int shardId;

  @Column(name = "bound_at", nullable = false)
  private Instant boundAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected CoordinatorSessionRouteEntity() {}

  public CoordinatorSessionRouteEntity(
      String sessionId,
      String tenantId,
      long routeEpoch,
      int virtualPartition,
      int shardId,
      Instant now) {
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.routeEpoch = routeEpoch;
    this.virtualPartition = virtualPartition;
    this.shardId = shardId;
    this.boundAt = now;
    this.updatedAt = now;
  }

  public void migrate(long routeEpoch, int virtualPartition, int shardId, Instant now) {
    if (routeEpoch <= this.routeEpoch) {
      throw new IllegalArgumentException("Route Epoch must increase");
    }
    this.routeEpoch = routeEpoch;
    this.virtualPartition = virtualPartition;
    this.shardId = shardId;
    this.updatedAt = now;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public long getRouteEpoch() {
    return routeEpoch;
  }

  public int getVirtualPartition() {
    return virtualPartition;
  }

  public int getShardId() {
    return shardId;
  }

  public Instant getBoundAt() {
    return boundAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
