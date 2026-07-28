package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "session_safety_lease_events")
public class SessionSafetyLeaseEventEntity {

  @Id
  @Column(name = "event_id")
  private String eventId;

  @Column(name = "lease_id", nullable = false)
  private String leaseId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "stream_sequence", insertable = false, updatable = false)
  private Long streamSequence;

  protected SessionSafetyLeaseEventEntity() {}

  public SessionSafetyLeaseEventEntity(
      String eventId,
      String leaseId,
      String sessionId,
      String tenantId,
      String eventType,
      Instant expiresAt,
      Instant occurredAt) {
    this.eventId = eventId;
    this.leaseId = leaseId;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.eventType = eventType;
    this.expiresAt = expiresAt;
    this.occurredAt = occurredAt;
  }

  public String getEventId() {
    return eventId;
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

  public String getEventType() {
    return eventType;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Long getStreamSequence() {
    return streamSequence;
  }
}
