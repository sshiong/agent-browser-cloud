package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 不可变、租户内哈希串联的审计事件。 */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

  @Id
  @Column(name = "event_id")
  private String eventId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "actor_type", nullable = false)
  private String actorType;

  @Column(name = "actor_id")
  private String actorId;

  @Column(name = "resource_type")
  private String resourceType;

  @Column(name = "resource_id")
  private String resourceId;

  @Column(name = "action", nullable = false)
  private String action;

  @Column(name = "result", nullable = false)
  private String result;

  @Column(name = "details", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String details;

  @Column(name = "sequence_no")
  private Long sequenceNo;

  @Column(name = "previous_event_hash")
  private String previousEventHash;

  @Column(name = "event_hash")
  private String eventHash;

  @Column(name = "request_id")
  private String requestId;

  @Column(name = "retention_until")
  private Instant retentionUntil;

  @Column(name = "legal_hold", nullable = false)
  private boolean legalHold;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public AuditEventEntity() {}

  public String getEventId() {
    return eventId;
  }

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getActorType() {
    return actorType;
  }

  public void setActorType(String actorType) {
    this.actorType = actorType;
  }

  public String getActorId() {
    return actorId;
  }

  public void setActorId(String actorId) {
    this.actorId = actorId;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public void setResourceId(String resourceId) {
    this.resourceId = resourceId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getResult() {
    return result;
  }

  public void setResult(String result) {
    this.result = result;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public Long getSequenceNo() {
    return sequenceNo;
  }

  public void setSequenceNo(Long sequenceNo) {
    this.sequenceNo = sequenceNo;
  }

  public String getPreviousEventHash() {
    return previousEventHash;
  }

  public void setPreviousEventHash(String previousEventHash) {
    this.previousEventHash = previousEventHash;
  }

  public String getEventHash() {
    return eventHash;
  }

  public void setEventHash(String eventHash) {
    this.eventHash = eventHash;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  public Instant getRetentionUntil() {
    return retentionUntil;
  }

  public void setRetentionUntil(Instant retentionUntil) {
    this.retentionUntil = retentionUntil;
  }

  public boolean isLegalHold() {
    return legalHold;
  }

  public void setLegalHold(boolean legalHold) {
    this.legalHold = legalHold;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
