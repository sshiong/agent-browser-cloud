package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "session_resource_events")
public class SessionResourceEventEntity {
  @Id private String eventId;

  @Column(nullable = false)
  private String sessionId;

  @Column(nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String eventType;

  @Column(nullable = false)
  private String reason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String oldResources;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String newResources;

  @Column(nullable = false)
  private String decisionSource;

  private String operationId;
  private String requestId;

  @Column(nullable = false)
  private String result;

  @Column(nullable = false)
  private Instant occurredAt;

  protected SessionResourceEventEntity() {}

  public SessionResourceEventEntity(
      String eventId,
      String sessionId,
      String tenantId,
      String eventType,
      String reason,
      String oldResources,
      String newResources,
      String decisionSource,
      String operationId,
      String requestId,
      String result,
      Instant occurredAt) {
    this.eventId = eventId;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.eventType = eventType;
    this.reason = reason;
    this.oldResources = oldResources;
    this.newResources = newResources;
    this.decisionSource = decisionSource;
    this.operationId = operationId;
    this.requestId = requestId;
    this.result = result;
    this.occurredAt = occurredAt;
  }

  public String getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getReason() {
    return reason;
  }

  public String getOldResources() {
    return oldResources;
  }

  public String getNewResources() {
    return newResources;
  }

  public String getDecisionSource() {
    return decisionSource;
  }

  public String getOperationId() {
    return operationId;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getResult() {
    return result;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
