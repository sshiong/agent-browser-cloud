package io.browsercloud.application;

import io.browsercloud.infrastructure.CoordinatorCommandQueue;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims only commands whose Session shard belongs to this physical Control Plane worker. */
@Component
public class RoutedCoordinatorCommandWorker {

  private static final Logger log = LoggerFactory.getLogger(RoutedCoordinatorCommandWorker.class);
  private static final int MAXIMUM_ATTEMPTS = 5;

  private final CoordinatorCommandQueue queue;
  private final RoutedCoordinatorCommandProcessor processor;

  public RoutedCoordinatorCommandWorker(
      CoordinatorCommandQueue queue, RoutedCoordinatorCommandProcessor processor) {
    this.queue = queue;
    this.processor = processor;
  }

  @Scheduled(fixedDelayString = "${coordinator.command-dispatch-interval-ms:100}")
  public void dispatch() {
    var now = Instant.now();
    queue.failExpired(now);
    for (var commandId : queue.claimReady(now)) {
      try {
        processor.process(commandId);
      } catch (RuntimeException exception) {
        var failureCode = stableFailureCode(exception);
        log.warn("Routed Coordinator command {} failed with {}", commandId, failureCode, exception);
        queue.retryOrFail(commandId, failureCode, Instant.now(), MAXIMUM_ATTEMPTS);
      }
    }
  }

  private static String stableFailureCode(RuntimeException exception) {
    var message = exception.getMessage();
    if (message != null && message.matches("[A-Z0-9_:.-]{3,240}")) {
      return message;
    }
    return "COORDINATOR_COMMAND_EXECUTION_FAILED";
  }
}
