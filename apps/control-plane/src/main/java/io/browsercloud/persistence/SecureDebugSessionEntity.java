package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "secure_debug_sessions")
public class SecureDebugSessionEntity {

  @Id
  @Column(name = "debug_session_id")
  private String debugSessionId;

  @Column(name = "break_glass_request_id", nullable = false, unique = true)
  private String breakGlassRequestId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "resource_type", nullable = false)
  private String resourceType;

  @Column(name = "resource_id", nullable = false)
  private String resourceId;

  @Column(name = "operator_id", nullable = false)
  private String operatorId;

  @Column(nullable = false)
  private String state;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "end_reason")
  private String endReason;

  @Column(name = "access_count", nullable = false)
  private int accessCount;

  @Column(name = "event_sequence", nullable = false)
  private long eventSequence;

  @Column(name = "last_access_at")
  private Instant lastAccessAt;

  @Column(name = "evidence_head_hash")
  private String evidenceHeadHash;

  @Version
  @Column(nullable = false)
  private long version;

  protected SecureDebugSessionEntity() {}

  public SecureDebugSessionEntity(
      String debugSessionId,
      String breakGlassRequestId,
      String tenantId,
      String resourceType,
      String resourceId,
      String operatorId,
      Instant startedAt,
      Instant expiresAt) {
    this.debugSessionId = debugSessionId;
    this.breakGlassRequestId = breakGlassRequestId;
    this.tenantId = tenantId;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
    this.operatorId = operatorId;
    this.startedAt = startedAt;
    this.expiresAt = expiresAt;
    state = "ACTIVE";
  }

  public void recordAccess(Instant now) {
    accessCount++;
    lastAccessAt = now;
  }

  public long nextEventSequence() {
    eventSequence++;
    return eventSequence;
  }

  public void advanceEvidence(String evidenceHash) {
    evidenceHeadHash = evidenceHash;
  }

  public void end(String newState, String reason, Instant now) {
    if (!"ACTIVE".equals(state)) {
      return;
    }
    state = newState;
    endReason = reason;
    endedAt = now;
  }

  public String getDebugSessionId() {
    return debugSessionId;
  }

  public String getBreakGlassRequestId() {
    return breakGlassRequestId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getResourceType() {
    return resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getOperatorId() {
    return operatorId;
  }

  public String getState() {
    return state;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public String getEndReason() {
    return endReason;
  }

  public int getAccessCount() {
    return accessCount;
  }

  public long getEventSequence() {
    return eventSequence;
  }

  public Instant getLastAccessAt() {
    return lastAccessAt;
  }

  public String getEvidenceHeadHash() {
    return evidenceHeadHash;
  }
}
