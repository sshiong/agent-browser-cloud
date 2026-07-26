package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "secure_debug_access_events")
public class SecureDebugAccessEventEntity {

  @Id
  @Column(name = "access_event_id")
  private String accessEventId;

  @Column(name = "debug_session_id", nullable = false)
  private String debugSessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "sequence_no", nullable = false)
  private long sequenceNo;

  @Column(name = "actor_id", nullable = false)
  private String actorId;

  @Column(nullable = false)
  private String action;

  @Column(nullable = false)
  private String result;

  @Column(name = "field_projection", nullable = false)
  private String fieldProjection;

  @Column(name = "previous_event_hash")
  private String previousEventHash;

  @Column(name = "evidence_hash", nullable = false, unique = true)
  private String evidenceHash;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected SecureDebugAccessEventEntity() {}

  public SecureDebugAccessEventEntity(
      String accessEventId,
      String debugSessionId,
      String tenantId,
      long sequenceNo,
      String actorId,
      String action,
      String result,
      String fieldProjection,
      String previousEventHash,
      String evidenceHash,
      Instant occurredAt) {
    this.accessEventId = accessEventId;
    this.debugSessionId = debugSessionId;
    this.tenantId = tenantId;
    this.sequenceNo = sequenceNo;
    this.actorId = actorId;
    this.action = action;
    this.result = result;
    this.fieldProjection = fieldProjection;
    this.previousEventHash = previousEventHash;
    this.evidenceHash = evidenceHash;
    this.occurredAt = occurredAt;
  }

  public String getAccessEventId() {
    return accessEventId;
  }

  public String getDebugSessionId() {
    return debugSessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public long getSequenceNo() {
    return sequenceNo;
  }

  public String getActorId() {
    return actorId;
  }

  public String getAction() {
    return action;
  }

  public String getResult() {
    return result;
  }

  public String getFieldProjection() {
    return fieldProjection;
  }

  public String getPreviousEventHash() {
    return previousEventHash;
  }

  public String getEvidenceHash() {
    return evidenceHash;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
