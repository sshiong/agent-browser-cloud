package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "session_safety_leases")
public class SessionSafetyLeaseEntity {

  @Id
  @Column(name = "lease_id")
  private String leaseId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "signal_type", nullable = false)
  private String signalType;

  @Column(name = "reason_code", nullable = false)
  private String reasonCode;

  @Column(name = "owner_actor_id", nullable = false)
  private String ownerActorId;

  @Column(nullable = false)
  private String state;

  @Column(name = "acquired_at", nullable = false)
  private Instant acquiredAt;

  @Column(name = "renewed_at", nullable = false)
  private Instant renewedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected SessionSafetyLeaseEntity() {}

  public SessionSafetyLeaseEntity(
      String leaseId,
      String sessionId,
      String tenantId,
      long contextEpoch,
      String signalType,
      String reasonCode,
      String ownerActorId,
      Instant acquiredAt,
      Instant expiresAt) {
    this.leaseId = leaseId;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.contextEpoch = contextEpoch;
    this.signalType = signalType;
    this.reasonCode = reasonCode;
    this.ownerActorId = ownerActorId;
    this.state = "ACTIVE";
    this.acquiredAt = acquiredAt;
    this.renewedAt = acquiredAt;
    this.expiresAt = expiresAt;
  }

  public void renew(Instant renewedAt, Instant expiresAt) {
    if (!"ACTIVE".equals(state) || !this.expiresAt.isAfter(renewedAt)) {
      throw new IllegalStateException("safety lease is no longer renewable");
    }
    this.renewedAt = renewedAt;
    this.expiresAt = expiresAt;
  }

  public boolean release(Instant now) {
    if (!"ACTIVE".equals(state)) return false;
    state = expiresAt.isAfter(now) ? "RELEASED" : "EXPIRED";
    releasedAt = now;
    return true;
  }

  public boolean expire(Instant now) {
    if (!"ACTIVE".equals(state) || expiresAt.isAfter(now)) return false;
    state = "EXPIRED";
    releasedAt = now;
    return true;
  }

  public String getLeaseId() {
    return leaseId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public String getSignalType() {
    return signalType;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public String getOwnerActorId() {
    return ownerActorId;
  }

  public String getState() {
    return state;
  }

  public Instant getAcquiredAt() {
    return acquiredAt;
  }

  public Instant getRenewedAt() {
    return renewedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getReleasedAt() {
    return releasedAt;
  }
}
