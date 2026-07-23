package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 已成功消费的事件，用于 Inbox 幂等去重。 */
@Entity
@Table(name = "inbox_events")
public class InboxEventEntity {

  @Id
  @Column(name = "event_id")
  private String eventId;

  @Column(name = "consumer_id", nullable = false)
  private String consumerId;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  public InboxEventEntity() {}

  public InboxEventEntity(String eventId, String consumerId, Instant processedAt) {
    this.eventId = eventId;
    this.consumerId = consumerId;
    this.processedAt = processedAt;
  }

  public String getEventId() {
    return eventId;
  }

  public String getConsumerId() {
    return consumerId;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
