package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.DomainEvent;
import io.browsercloud.coordinator.OutboxPublisher;
import io.browsercloud.persistence.OutboxEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox Publisher PostgreSQL 实现。
 *
 * <p>使用 Transactional Outbox 模式保证事件发布与数据库操作的原子性。
 */
@Component
public class PostgresOutboxPublisher implements OutboxPublisher {

  private final OutboxEventJpaRepository outboxJpa;
  private final ObjectMapper objectMapper;

  public PostgresOutboxPublisher(OutboxEventJpaRepository outboxJpa, ObjectMapper objectMapper) {
    this.outboxJpa = outboxJpa;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void append(DomainEvent event) {
    var entity = new OutboxEventEntity();
    entity.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    entity.setAggregateType(event.aggregateType());
    entity.setAggregateId(event.aggregateId());
    entity.setEventType(event.eventType());
    entity.setSchemaVersion(1);
    entity.setCreatedAt(Instant.now());
    entity.setNextAttemptAt(Instant.now());

    entity.setPayload(serialize(event));

    outboxJpa.save(entity);
  }

  private String serialize(DomainEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize domain event", exception);
    }
  }
}
