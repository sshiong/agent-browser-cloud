package io.browsercloud.application;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.persistence.DurableWorkflowJpaRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restart-safe phase deadline scanner; every mutation is persisted before compensation. */
@Component
public class DurableWorkflowDeadlineScanner {

  private static final Logger log = LoggerFactory.getLogger(DurableWorkflowDeadlineScanner.class);

  private final DurableWorkflowJpaRepository repository;
  private final CoordinatorCommandRoutingService commandRouting;

  public DurableWorkflowDeadlineScanner(
      DurableWorkflowJpaRepository repository, CoordinatorCommandRoutingService commandRouting) {
    this.repository = repository;
    this.commandRouting = commandRouting;
  }

  @Scheduled(fixedDelayString = "${workflow.deadline-scan-interval-ms:1000}")
  public void scan() {
    for (var candidate : repository.findExpired(Instant.now(), PageRequest.of(0, 100))) {
      try {
        commandRouting.enqueueAsync(
            candidate.getSessionId(),
            WORKFLOW_TIMEOUT,
            "workflow-timeout:" + candidate.getWorkflowId(),
            new WorkflowTimeout(candidate.getWorkflowId()),
            Duration.ofMinutes(5));
      } catch (RuntimeException exception) {
        log.warn(
            "Durable workflow {} timeout routing failed", candidate.getWorkflowId(), exception);
      }
    }
  }
}
