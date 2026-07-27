package io.browsercloud.application;

import io.browsercloud.coordinator.OperationTimedOut;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Times out Resource Adjustment Operations that are not owned by a Durable Workflow. */
@Component
public class ExclusiveOperationDeadlineScanner {

  private static final Logger log =
      LoggerFactory.getLogger(ExclusiveOperationDeadlineScanner.class);

  private final ExclusiveOperationJpaRepository operations;
  private final SessionCoordinator coordinator;

  public ExclusiveOperationDeadlineScanner(
      ExclusiveOperationJpaRepository operations, SessionCoordinator coordinator) {
    this.operations = operations;
    this.coordinator = coordinator;
  }

  @Scheduled(fixedDelayString = "${operation.deadline-scan-interval-ms:1000}")
  public void scan() {
    for (var operation :
        operations.findExpiredWithoutWorkflow(Instant.now(), PageRequest.of(0, 100))) {
      try {
        coordinator.handle(
            new OperationTimedOut(operation.getSessionId(), operation.getOperationId()));
      } catch (RuntimeException exception) {
        log.warn("Operation {} timeout processing failed", operation.getOperationId(), exception);
      }
    }
  }
}
