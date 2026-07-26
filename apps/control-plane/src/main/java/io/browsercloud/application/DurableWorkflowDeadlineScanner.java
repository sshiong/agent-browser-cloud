package io.browsercloud.application;

import io.browsercloud.coordinator.OperationTimedOut;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.persistence.DurableWorkflowJpaRepository;
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
  private final DurableWorkflowApplicationService workflowService;
  private final SessionCoordinator coordinator;
  private final StaticProxyApplicationService proxyService;

  public DurableWorkflowDeadlineScanner(
      DurableWorkflowJpaRepository repository,
      DurableWorkflowApplicationService workflowService,
      SessionCoordinator coordinator,
      StaticProxyApplicationService proxyService) {
    this.repository = repository;
    this.workflowService = workflowService;
    this.coordinator = coordinator;
    this.proxyService = proxyService;
  }

  @Scheduled(fixedDelayString = "${workflow.deadline-scan-interval-ms:1000}")
  public void scan() {
    for (var candidate : repository.findExpired(Instant.now(), PageRequest.of(0, 100))) {
      try {
        var decision = workflowService.timeout(candidate, "PHASE_DEADLINE_EXCEEDED");
        if (!decision.timedOut()) {
          continue;
        }
        var workflow = decision.workflow();
        coordinator.handle(
            new OperationTimedOut(workflow.getSessionId(), workflow.getOperationId()));
        if ("RELEASE_PROXY".equals(workflow.getCompensationAction())) {
          proxyService.release(workflow.getSessionId());
          workflowService.markCompensated(workflow, "proxy-released");
        } else {
          workflowService.deadLetter(workflow, "NO_SAFE_AUTOMATIC_COMPENSATION");
        }
      } catch (RuntimeException exception) {
        log.warn(
            "Durable workflow {} timeout processing failed", candidate.getWorkflowId(), exception);
        try {
          workflowService.deadLetter(candidate, "COMPENSATION_FAILED");
        } catch (RuntimeException deadLetterFailure) {
          log.error(
              "Durable workflow {} could not enter DLQ",
              candidate.getWorkflowId(),
              deadLetterFailure);
        }
      }
    }
  }
}
