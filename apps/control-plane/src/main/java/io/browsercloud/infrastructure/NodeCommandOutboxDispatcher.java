package io.browsercloud.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.proto.node.v1.CommandEnvelope;
import io.browsercloud.proto.node.v1.DispatchRequest;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在 Coordinator 事务之外，将已提交的 Node Command Outbox 事件投递到 Browser Node。 */
@Component
public class NodeCommandOutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NodeCommandOutboxDispatcher.class);
  private static final int MAX_ATTEMPTS = 10;
  private static final Set<String> TERMINAL_REJECTIONS =
      Set.of("UNSUPPORTED_COMMAND", "STALE_COORDINATOR_TERM");

  private final OutboxEventJpaRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final ManagedChannel channel;

  public NodeCommandOutboxDispatcher(
      OutboxEventJpaRepository outboxRepository,
      ObjectMapper objectMapper,
      @Value("${browser-node.grpc-target:localhost:9090}") String grpcTarget) {
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.channel = ManagedChannelBuilder.forTarget(grpcTarget).usePlaintext().build();
  }

  @Scheduled(fixedDelayString = "${browser-node.dispatch-interval-ms:250}")
  public void dispatchPending() {
    var events =
        outboxRepository
            .findTop100ByPublishedAtIsNullAndDeadLetteredAtIsNullAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                PostgresNodeCommandGateway.NODE_COMMAND_EVENT, Instant.now());
    for (var event : events) {
      try {
        var command = objectMapper.readValue(event.getPayload(), NodeCommand.class);
        var response =
            NodeControlServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(2, TimeUnit.SECONDS)
                .dispatch(DispatchRequest.newBuilder().setCommand(toEnvelope(command)).build());
        var acknowledgement = response.getAcknowledgement();
        if (acknowledgement.getAccepted()) {
          event.setPublishedAt(Instant.now());
          outboxRepository.save(event);
        } else {
          recordFailure(
              event,
              acknowledgement.getErrorCode(),
              TERMINAL_REJECTIONS.contains(acknowledgement.getErrorCode()));
          log.warn(
              "Browser Node rejected command {} with code {}",
              command.messageId(),
              acknowledgement.getErrorCode());
        }
      } catch (Exception exception) {
        recordFailure(event, "NODE_UNAVAILABLE", false);
        log.debug(
            "Browser Node command dispatch deferred for event {}: {}",
            event.getEventId(),
            exception.getMessage());
      }
    }
  }

  private void recordFailure(
      io.browsercloud.persistence.OutboxEventEntity event, String errorCode, boolean terminal) {
    int attempts = event.getPublishAttempts() + 1;
    event.setPublishAttempts(attempts);
    event.setLastError(errorCode);
    if (terminal || attempts >= MAX_ATTEMPTS) {
      event.setDeadLetteredAt(Instant.now());
    } else {
      long backoffSeconds = Math.min(60L, 1L << Math.min(attempts, 6));
      long jitterMillis = Math.floorMod(event.getEventId().hashCode(), 1000);
      event.setNextAttemptAt(
          Instant.now().plus(Duration.ofSeconds(backoffSeconds)).plusMillis(jitterMillis));
    }
    outboxRepository.save(event);
  }

  private CommandEnvelope toEnvelope(NodeCommand command) {
    return CommandEnvelope.newBuilder()
        .setMessageId(command.messageId())
        .setCommandType(command.commandType())
        .setTenantId(command.tenantId())
        .setSessionId(command.sessionId())
        .setCoordinatorTerm(command.coordinatorTerm())
        .setContextEpoch(command.contextEpoch())
        .setOperationEpoch(command.operationEpoch())
        .setIdempotencyKey(command.idempotencyKey())
        .setPayload(ByteString.copyFrom(command.payload()))
        .build();
  }

  @PreDestroy
  void closeChannel() {
    channel.shutdown();
  }
}
