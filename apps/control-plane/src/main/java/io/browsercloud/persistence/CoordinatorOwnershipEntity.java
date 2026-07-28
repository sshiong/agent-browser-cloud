package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** Coordinator Ownership JPA 实体。 */
@Entity
@Table(name = "coordinator_ownership")
public class CoordinatorOwnershipEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "coordinator_owner", nullable = false)
  private String coordinatorOwner;

  @Column(name = "coordinator_term", nullable = false)
  private long coordinatorTerm;

  @Column(name = "route_epoch", nullable = false)
  private long routeEpoch;

  @Column(name = "owner_heartbeat_at", nullable = false)
  private Instant ownerHeartbeatAt;

  @Column(name = "claimed_at", nullable = false)
  private Instant claimedAt;

  public CoordinatorOwnershipEntity() {}

  // Getters and Setters
  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getCoordinatorOwner() {
    return coordinatorOwner;
  }

  public void setCoordinatorOwner(String coordinatorOwner) {
    this.coordinatorOwner = coordinatorOwner;
  }

  public long getCoordinatorTerm() {
    return coordinatorTerm;
  }

  public void setCoordinatorTerm(long coordinatorTerm) {
    this.coordinatorTerm = coordinatorTerm;
  }

  public long getRouteEpoch() {
    return routeEpoch;
  }

  public void setRouteEpoch(long routeEpoch) {
    this.routeEpoch = routeEpoch;
  }

  public Instant getOwnerHeartbeatAt() {
    return ownerHeartbeatAt;
  }

  public void setOwnerHeartbeatAt(Instant ownerHeartbeatAt) {
    this.ownerHeartbeatAt = ownerHeartbeatAt;
  }

  public Instant getClaimedAt() {
    return claimedAt;
  }

  public void setClaimedAt(Instant claimedAt) {
    this.claimedAt = claimedAt;
  }
}
