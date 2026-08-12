package io.browsercloud.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.browsercloud.application.AgentActionPayloadService;
import io.browsercloud.application.AgentExecutionWaitProjectionService;
import io.browsercloud.application.SessionEvidenceGovernanceStore;
import io.browsercloud.application.SessionResourceAdjustmentLifecycleService;
import io.browsercloud.coordinator.CoordinatorRouteAuthority;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.AgentActionCommand;
import io.browsercloud.proto.node.v1.AgentNavigateCommand;
import io.browsercloud.proto.node.v1.CommandEnvelope;
import io.browsercloud.proto.node.v1.DispatchRequest;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.grpc.ManagedChannel;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
  private static final String HUMAN_INPUT_PRIORITY = "HUMAN_INPUT_PRIORITY";
  private static final Set<String> TERMINAL_REJECTIONS =
      Set.of(
          "UNSUPPORTED_COMMAND",
          "STALE_COORDINATOR_TERM",
          "STALE_ROUTE_EPOCH",
          "ROUTE_EPOCH_REQUIRED",
          "WRONG_COORDINATOR_SHARD");

  private final OutboxEventJpaRepository outboxRepository;
  private final NodeCommandDispatchClaimService claimService;
  private final ObjectMapper objectMapper;
  private final AgentActionPayloadService actionPayloadService;
  private final AgentExecutionWaitProjectionService executionWaitProjection;
  private final SessionEvidenceGovernanceStore evidenceGovernance;
  private final SessionResourceAdjustmentLifecycleService resourceAdjustments;
  private final CoordinatorRouteAuthority routeAuthority;
  private final BrowserNodeJpaRepository browserNodeRepository;
  private final GrpcTransportFactory transportFactory;
  private final String legacyGrpcTarget;
  private final Map<String, NodeChannel> nodeChannels = new HashMap<>();

  public NodeCommandOutboxDispatcher(
      OutboxEventJpaRepository outboxRepository,
      NodeCommandDispatchClaimService claimService,
      ObjectMapper objectMapper,
      AgentActionPayloadService actionPayloadService,
      AgentExecutionWaitProjectionService executionWaitProjection,
      SessionEvidenceGovernanceStore evidenceGovernance,
      SessionResourceAdjustmentLifecycleService resourceAdjustments,
      CoordinatorRouteAuthority routeAuthority,
      BrowserNodeJpaRepository browserNodeRepository,
      GrpcTransportFactory transportFactory,
      @Value("${browser-node.grpc-target:localhost:9090}") String grpcTarget) {
    this.outboxRepository = outboxRepository;
    this.claimService = claimService;
    this.objectMapper = objectMapper;
    this.actionPayloadService = actionPayloadService;
    this.executionWaitProjection = executionWaitProjection;
    this.evidenceGovernance = evidenceGovernance;
    this.resourceAdjustments = resourceAdjustments;
    this.routeAuthority = routeAuthority;
    this.browserNodeRepository = browserNodeRepository;
    this.transportFactory = transportFactory;
    this.legacyGrpcTarget = grpcTarget;
  }

  @Scheduled(fixedDelayString = "${browser-node.dispatch-interval-ms:250}")
  public void dispatchPending() {
    var eventIds = claimService.claimReady(Instant.now());
    for (var eventId : eventIds) {
      var event = outboxRepository.findById(eventId).orElse(null);
      if (event == null || !claimService.workerId().equals(event.getDispatchOwner())) {
        continue;
      }
      try {
        var command = objectMapper.readValue(event.getPayload(), NodeCommand.class);
        assertCurrentRoute(event, command);
        var channel = channelFor(command);
        markResourceAdjustmentExecuting(command);
        var response =
            NodeControlServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .dispatch(
                    DispatchRequest.newBuilder().setCommand(toEnvelope(event, command)).build());
        var acknowledgement = response.getAcknowledgement();
        if (acknowledgement.getAccepted()) {
          clearHumanInputWait(command);
          event.setPublishedAt(Instant.now());
          event.releaseDispatchClaim();
          outboxRepository.save(event);
        } else {
          if (HUMAN_INPUT_PRIORITY.equals(acknowledgement.getErrorCode())) {
            if (!HUMAN_INPUT_PRIORITY.equals(event.getLastError())) {
              projectHumanInputWait(command);
            }
            recordHumanInputDeferral(event);
            log.debug(
                "Browser Node deferred Agent command {} while human input has priority",
                command.messageId());
            continue;
          }
          clearHumanInputWait(command);
          recordFailure(
              event,
              acknowledgement.getErrorCode(),
              TERMINAL_REJECTIONS.contains(acknowledgement.getErrorCode()));
          failResourceAdjustmentIfDeadLettered(event, command, acknowledgement.getErrorCode());
          failEvidenceCaptureIfDeadLettered(event, acknowledgement.getErrorCode());
          log.warn(
              "Browser Node rejected command {} with code {}",
              command.messageId(),
              acknowledgement.getErrorCode());
        }
      } catch (StaleRouteException exception) {
        recordFailure(event, exception.errorCode(), true);
        failResourceAdjustmentIfDeadLettered(event, exception.errorCode());
        failEvidenceCaptureIfDeadLettered(event, exception.errorCode());
        log.warn(
            "Rejected stale routed Node Command event {} with code {}",
            event.getEventId(),
            exception.errorCode());
      } catch (Exception exception) {
        recordFailure(event, "NODE_UNAVAILABLE", false);
        failResourceAdjustmentIfDeadLettered(event, "NODE_UNAVAILABLE");
        failEvidenceCaptureIfDeadLettered(event, "NODE_UNAVAILABLE");
        log.debug(
            "Browser Node command dispatch deferred for event {}: {}",
            event.getEventId(),
            exception.getMessage());
      }
    }
  }

  private ManagedChannel channelFor(NodeCommand command) {
    String nodeId = command.nodeId();
    if (nodeId == null || nodeId.isBlank()) {
      // N/N-1 compatibility for commands created before node_id became part of the outbox payload.
      return channelForTarget("legacy", legacyGrpcTarget);
    }
    var node =
        browserNodeRepository
            .findById(nodeId)
            .orElseThrow(() -> new IllegalStateException("PLACEMENT_NODE_NOT_REGISTERED"));
    if (!node.isReadyForDispatch()) {
      throw new IllegalStateException("PLACEMENT_NODE_NOT_READY");
    }
    return channelForTarget(nodeId, node.getGrpcTarget());
  }

  private ManagedChannel channelForTarget(String channelKey, String target) {
    var existing = nodeChannels.get(channelKey);
    if (existing != null && existing.target().equals(target) && !existing.channel().isShutdown()) {
      return existing.channel();
    }
    if (existing != null) {
      existing.channel().shutdown();
    }
    var replacement = new NodeChannel(target, transportFactory.nodeChannel(target));
    nodeChannels.put(channelKey, replacement);
    return replacement.channel();
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
    event.releaseDispatchClaim();
    outboxRepository.save(event);
  }

  private void markResourceAdjustmentExecuting(NodeCommand command) {
    if (!"AdjustRuntimeResources".equals(command.commandType())) return;
    resourceAdjustments.executing(command.sessionId(), command.idempotencyKey());
  }

  private void failResourceAdjustmentIfDeadLettered(
      io.browsercloud.persistence.OutboxEventEntity event, String errorCode) {
    if (event.getDeadLetteredAt() == null) return;
    try {
      var command = objectMapper.readValue(event.getPayload(), NodeCommand.class);
      failResourceAdjustmentIfDeadLettered(event, command, errorCode);
    } catch (Exception exception) {
      log.warn("Failed to decode dead-lettered Node command {}", event.getEventId(), exception);
    }
  }

  private void failResourceAdjustmentIfDeadLettered(
      io.browsercloud.persistence.OutboxEventEntity event, NodeCommand command, String errorCode) {
    if (event.getDeadLetteredAt() == null
        || !"AdjustRuntimeResources".equals(command.commandType())) return;
    try {
      resourceAdjustments.dispatchFailed(command.sessionId(), command.idempotencyKey(), errorCode);
    } catch (RuntimeException exception) {
      log.warn(
          "Failed to finalize resource adjustment {} after Node command dead letter",
          command.idempotencyKey(),
          exception);
    }
  }

  /**
   * 真人输入优先是瞬时仲裁结果，不是命令失败。保持原 publishAttempts，使同一条 Agent 命令可以在真人停止输入后继续，且不会因人工协作时间较长进入 Dead Letter。
   */
  private void recordHumanInputDeferral(io.browsercloud.persistence.OutboxEventEntity event) {
    event.setLastError(HUMAN_INPUT_PRIORITY);
    event.setNextAttemptAt(Instant.now().plusMillis(500));
    event.releaseDispatchClaim();
    outboxRepository.save(event);
  }

  private void projectHumanInputWait(NodeCommand command) {
    var taskId = agentTaskId(command);
    if (taskId == null) return;
    try {
      executionWaitProjection.waitForHumanInput(
          taskId, command.tenantId(), command.sessionId(), Instant.now());
    } catch (RuntimeException exception) {
      log.warn(
          "Failed to publish human-input wait state for Agent command {}",
          command.messageId(),
          exception);
    }
  }

  private void clearHumanInputWait(NodeCommand command) {
    var taskId = agentTaskId(command);
    if (taskId == null) return;
    try {
      executionWaitProjection.resumeAfterHumanInput(
          taskId, command.tenantId(), command.sessionId(), Instant.now());
    } catch (RuntimeException exception) {
      log.warn(
          "Failed to clear human-input wait state for Agent command {}",
          command.messageId(),
          exception);
    }
  }

  private String agentTaskId(NodeCommand command) {
    try {
      return switch (command.commandType()) {
        case "AgentAction" -> AgentActionCommand.parseFrom(command.payload()).getTaskId();
        case "AgentNavigate" -> AgentNavigateCommand.parseFrom(command.payload()).getTaskId();
        default -> null;
      };
    } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
      log.warn("Agent command {} has an invalid payload", command.messageId());
      return null;
    }
  }

  private void assertCurrentRoute(
      io.browsercloud.persistence.OutboxEventEntity event, NodeCommand command) {
    if (event.getRouteEpoch() == null && event.getCoordinatorShardId() == null) {
      return;
    }
    if (event.getRouteEpoch() == null || event.getCoordinatorShardId() == null) {
      throw new StaleRouteException("INVALID_ROUTE_BINDING");
    }
    var current = routeAuthority.resolve(command.sessionId());
    if (current.routeEpoch() != event.getRouteEpoch()) {
      throw new StaleRouteException("STALE_ROUTE_EPOCH");
    }
    if (current.shardId() != event.getCoordinatorShardId()) {
      throw new StaleRouteException("WRONG_COORDINATOR_SHARD");
    }
  }

  private CommandEnvelope toEnvelope(
      io.browsercloud.persistence.OutboxEventEntity event, NodeCommand command) {
    var builder =
        CommandEnvelope.newBuilder()
            .setMessageId(command.messageId())
            .setCommandType(command.commandType())
            .setTenantId(command.tenantId())
            .setSessionId(command.sessionId())
            .setCoordinatorTerm(command.coordinatorTerm())
            .setContextEpoch(command.contextEpoch())
            .setOperationEpoch(command.operationEpoch())
            .setIdempotencyKey(command.idempotencyKey())
            .setPayload(ByteString.copyFrom(outboundPayload(command)));
    if (event.getRouteEpoch() != null && event.getCoordinatorShardId() != null) {
      builder
          .setRouteEpoch(event.getRouteEpoch())
          .setCoordinatorShardId(event.getCoordinatorShardId());
    }
    return builder.build();
  }

  private byte[] outboundPayload(NodeCommand command) {
    if (!command.commandType().equals("AgentAction")) {
      return command.payload();
    }
    try {
      var payload = AgentActionCommand.parseFrom(command.payload());
      if (!payload.getToolId().equals("TYPE_TEXT")) {
        return command.payload();
      }
      if (payload.getSealedText().isBlank() || !payload.getText().isBlank()) {
        throw new IllegalArgumentException("Agent TypeText payload envelope is invalid");
      }
      var plaintext =
          actionPayloadService.unseal(
              command.tenantId(),
              payload.getTaskId(),
              payload.getStepId(),
              payload.getSealedText());
      return payload.toBuilder().clearSealedText().setText(plaintext).build().toByteArray();
    } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
      throw new IllegalArgumentException("Agent action payload is invalid protobuf", exception);
    }
  }

  private void failEvidenceCaptureIfDeadLettered(
      io.browsercloud.persistence.OutboxEventEntity event, String errorCode) {
    if (event.getDeadLetteredAt() == null) {
      return;
    }
    try {
      var command = objectMapper.readValue(event.getPayload(), NodeCommand.class);
      if ("CaptureObserverScreenshot".equals(command.commandType())) {
        evidenceGovernance.failCaptureDispatch(command.messageId(), errorCode, Instant.now());
      }
    } catch (Exception exception) {
      log.debug(
          "Could not reconcile dead-lettered evidence command for event {}",
          event.getEventId(),
          exception);
    }
  }

  @PreDestroy
  void closeChannels() {
    try {
      claimService.unregister();
    } catch (RuntimeException failure) {
      log.debug("Failed to unregister Node Command dispatch worker during shutdown", failure);
    }
    nodeChannels.values().forEach(nodeChannel -> nodeChannel.channel().shutdown());
    nodeChannels.clear();
  }

  private record NodeChannel(String target, ManagedChannel channel) {}

  private static final class StaleRouteException extends RuntimeException {
    private final String errorCode;

    private StaleRouteException(String errorCode) {
      super(errorCode);
      this.errorCode = errorCode;
    }

    private String errorCode() {
      return errorCode;
    }
  }
}
