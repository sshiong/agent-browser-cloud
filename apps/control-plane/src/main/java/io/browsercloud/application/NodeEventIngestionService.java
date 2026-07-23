package io.browsercloud.application;

import io.browsercloud.coordinator.CoordinatorResult;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionCoordinator;
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

  public NodeEventIngestionService(
      InboxEventJpaRepository inboxRepository, SessionCoordinator coordinator) {
    this.inboxRepository = inboxRepository;
    this.coordinator = coordinator;
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

    inboxRepository.save(new InboxEventEntity(command.eventId(), CONSUMER_ID, Instant.now()));
    return new Receipt(false);
  }

  public record Receipt(boolean duplicate) {}

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
