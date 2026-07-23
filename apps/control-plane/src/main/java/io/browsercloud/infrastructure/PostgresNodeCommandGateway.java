package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.persistence.OutboxEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 将 Node Command 持久化到 PostgreSQL Outbox。
 *
 * <p>Coordinator 的事务热路径不执行网络 I/O。后台 {@link NodeCommandOutboxDispatcher} 负责通过 gRPC 投递，Browser Node 以
 * message_id 去重。
 */
@Component
public class PostgresNodeCommandGateway implements NodeCommandGateway {

  static final String NODE_COMMAND_EVENT = "node.command.requested";

  private final OutboxEventJpaRepository outboxRepository;
  private final ObjectMapper objectMapper;

  public PostgresNodeCommandGateway(
      OutboxEventJpaRepository outboxRepository, ObjectMapper objectMapper) {
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public void send(NodeCommand command) {
    var entity = new OutboxEventEntity();
    entity.setEventId(newId("evt_"));
    entity.setAggregateType("session");
    entity.setAggregateId(command.sessionId());
    entity.setEventType(NODE_COMMAND_EVENT);
    entity.setSchemaVersion(1);
    entity.setPayload(serialize(command));
    entity.setCreatedAt(Instant.now());
    entity.setNextAttemptAt(Instant.now());
    outboxRepository.save(entity);
  }

  private String serialize(NodeCommand command) {
    try {
      return objectMapper.writeValueAsString(command);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize Node command", exception);
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
