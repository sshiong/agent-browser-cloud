package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Outbox Event JPA 实体。 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

  @Id
  @Column(name = "event_id")
  private String eventId;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private String aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion;

  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "publish_attempts", nullable = false)
  private int publishAttempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "last_error")
  private String lastError;

  @Column(name = "dead_lettered_at")
  private Instant deadLetteredAt;

  @Column(name = "route_epoch")
  private Long routeEpoch;

  @Column(name = "coordinator_shard_id")
  private Integer coordinatorShardId;

  @Column(name = "dispatch_owner")
  private String dispatchOwner;

  @Column(name = "dispatch_lease_until")
  private Instant dispatchLeaseUntil;

  public OutboxEventEntity() {}

  // Getters and Setters
  public String getEventId() {
    return eventId;
  }

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public void setAggregateType(String aggregateType) {
    this.aggregateType = aggregateType;
  }

  public String getAggregateId() {
    return aggregateId;
  }

  public void setAggregateId(String aggregateId) {
    this.aggregateId = aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(int schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String payload) {
    this.payload = payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }

  public int getPublishAttempts() {
    return publishAttempts;
  }

  public void setPublishAttempts(int publishAttempts) {
    this.publishAttempts = publishAttempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public void setNextAttemptAt(Instant nextAttemptAt) {
    this.nextAttemptAt = nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public Instant getDeadLetteredAt() {
    return deadLetteredAt;
  }

  public void setDeadLetteredAt(Instant deadLetteredAt) {
    this.deadLetteredAt = deadLetteredAt;
  }

  public Long getRouteEpoch() {
    return routeEpoch;
  }

  public void setRouteEpoch(Long routeEpoch) {
    this.routeEpoch = routeEpoch;
  }

  public Integer getCoordinatorShardId() {
    return coordinatorShardId;
  }

  public void setCoordinatorShardId(Integer coordinatorShardId) {
    this.coordinatorShardId = coordinatorShardId;
  }

  public String getDispatchOwner() {
    return dispatchOwner;
  }

  public void setDispatchOwner(String dispatchOwner) {
    this.dispatchOwner = dispatchOwner;
  }

  public Instant getDispatchLeaseUntil() {
    return dispatchLeaseUntil;
  }

  public void setDispatchLeaseUntil(Instant dispatchLeaseUntil) {
    this.dispatchLeaseUntil = dispatchLeaseUntil;
  }

  public void releaseDispatchClaim() {
    dispatchOwner = null;
    dispatchLeaseUntil = null;
  }
}
