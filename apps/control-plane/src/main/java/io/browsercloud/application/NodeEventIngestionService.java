package io.browsercloud.application;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.CoordinatorResult;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.InboxEventEntity;
import io.browsercloud.persistence.InboxEventJpaRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在单一事务内完成 Node Event Inbox 去重和 Coordinator 状态提交。 */
@Service
public class NodeEventIngestionService {

  static final String CONSUMER_ID = "session-coordinator-v1";

  private final InboxEventJpaRepository inboxRepository;
  private final SessionCoordinator coordinator;
  private final BrowserStateRepository browserStateRepository;
  private final ProfileApplicationService profileApplicationService;
  private final StaticProxyApplicationService proxyApplicationService;
  private final SessionRepository sessionRepository;
  private final NodeCommandGateway nodeCommandGateway;

  public NodeEventIngestionService(
      InboxEventJpaRepository inboxRepository,
      SessionCoordinator coordinator,
      BrowserStateRepository browserStateRepository,
      ProfileApplicationService profileApplicationService,
      StaticProxyApplicationService proxyApplicationService,
      SessionRepository sessionRepository,
      NodeCommandGateway nodeCommandGateway) {
    this.inboxRepository = inboxRepository;
    this.coordinator = coordinator;
    this.browserStateRepository = browserStateRepository;
    this.profileApplicationService = profileApplicationService;
    this.proxyApplicationService = proxyApplicationService;
    this.sessionRepository = sessionRepository;
    this.nodeCommandGateway = nodeCommandGateway;
  }

  @Transactional
  public Receipt receive(NodeEventReceived command) {
    if (inboxRepository.existsById(command.eventId())) {
      return new Receipt(true);
    }

    var result = coordinator.handle(command);
    if (result.status() == CoordinatorResult.Status.REJECTED) {
      throw new NodeEventRejectedException(result.reason());
    }
    switch (command.event()) {
      case NodeEvent.StateUpdated state ->
          browserStateRepository.save(command.tenantId(), command.contextEpoch(), state);
      case NodeEvent.StateDiff diff -> {
        if (!browserStateRepository.applyDiff(command.tenantId(), command.contextEpoch(), diff)) {
          browserStateRepository.invalidate(
              command.tenantId(),
              command.contextEpoch(),
              command.sessionId(),
              diff.stateVersion(),
              "BASE_VERSION_MISMATCH");
          requestAutomaticFullResync(command, "BASE_VERSION_MISMATCH", "document");
        }
      }
      case NodeEvent.DiffTruncated truncated -> {
        browserStateRepository.invalidate(
            command.tenantId(),
            command.contextEpoch(),
            command.sessionId(),
            truncated.currentStateVersion(),
            truncated.reason());
        requestAutomaticFullResync(command, truncated.reason(), truncated.affectedRoot());
      }
      case NodeEvent.HumanTakeoverReady ready ->
          browserStateRepository.save(command.tenantId(), command.contextEpoch(), ready.state());
      case NodeEvent.HumanTakeoverEnded ended ->
          browserStateRepository.save(command.tenantId(), command.contextEpoch(), ended.state());
      default -> {}
    }
    if (command.event() instanceof NodeEvent.RuntimeStopped stopped
        && !stopped.checkpointId().isBlank()) {
      profileApplicationService.recordCheckpoint(command.tenantId(), stopped);
    }
    if (command.event() instanceof NodeEvent.RuntimeStarted started) {
      proxyApplicationService.recordBound(command.tenantId(), started);
    } else if (command.event() instanceof NodeEvent.RuntimeStopped) {
      proxyApplicationService.release(command.sessionId());
    }

    inboxRepository.save(new InboxEventEntity(command.eventId(), CONSUMER_ID, Instant.now()));
    return new Receipt(false);
  }

  public record Receipt(boolean duplicate) {}

  private void requestAutomaticFullResync(
      NodeEventReceived event, String reason, String affectedRoot) {
    var session = sessionRepository.require(event.sessionId());
    nodeCommandGateway.send(
        NodeCommands.requestStateResync(
            session, "FULL", affectedRoot, "AUTO_" + reason, "state-event:" + event.eventId()));
  }

  public static final class NodeEventRejectedException extends RuntimeException {
    private final String reason;

    public NodeEventRejectedException(String reason) {
      super("Node event rejected: " + reason);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }
}
