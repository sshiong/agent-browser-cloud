package io.browsercloud.application;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Times out expired Operations that are not owned by a Durable Workflow. */
@Component
public class ExclusiveOperationDeadlineScanner {

  private static final Logger log =
      LoggerFactory.getLogger(ExclusiveOperationDeadlineScanner.class);

  private final ExclusiveOperationJpaRepository operations;
  private final CoordinatorCommandRoutingService commandRouting;

  public ExclusiveOperationDeadlineScanner(
      ExclusiveOperationJpaRepository operations, CoordinatorCommandRoutingService commandRouting) {
    this.operations = operations;
    this.commandRouting = commandRouting;
  }

  @Scheduled(fixedDelayString = "${operation.deadline-scan-interval-ms:1000}")
  public void scan() {
    for (var operation :
        operations.findExpiredWithoutWorkflow(Instant.now(), PageRequest.of(0, 100))) {
      try {
        commandRouting.enqueueAsync(
            operation.getSessionId(),
            OPERATION_TIMEOUT,
            "operation-timeout:" + operation.getOperationId(),
            new OperationTimeout(operation.getOperationId()),
            Duration.ofMinutes(5));
      } catch (RuntimeException exception) {
        log.warn("Operation {} timeout routing failed", operation.getOperationId(), exception);
      }
    }
  }
}
