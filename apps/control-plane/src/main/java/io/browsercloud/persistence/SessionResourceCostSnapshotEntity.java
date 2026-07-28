package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "session_resource_cost_snapshots")
public class SessionResourceCostSnapshotEntity {
  @Id private String snapshotId;

  @Column(nullable = false)
  private String sessionId;

  @Column(nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String nodeId;

  @Column(nullable = false)
  private String pricingVersion;

  @Column(nullable = false)
  private BigDecimal hourlyCost;

  @Column(nullable = false)
  private Instant observedAt;

  protected SessionResourceCostSnapshotEntity() {}

  public SessionResourceCostSnapshotEntity(
      String snapshotId,
      String sessionId,
      String tenantId,
      String nodeId,
      String pricingVersion,
      BigDecimal hourlyCost,
      Instant observedAt) {
    this.snapshotId = snapshotId;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.nodeId = nodeId;
    this.pricingVersion = pricingVersion;
    this.hourlyCost = hourlyCost;
    this.observedAt = observedAt;
  }

  public String getSnapshotId() {
    return snapshotId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getPricingVersion() {
    return pricingVersion;
  }

  public BigDecimal getHourlyCost() {
    return hourlyCost;
  }

  public Instant getObservedAt() {
    return observedAt;
  }
}
