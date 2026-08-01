package io.browsercloud.application;

import io.browsercloud.infrastructure.WorkspaceMetadataBatchClaimStore;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims and executes bounded metadata mutations without an in-memory work queue. */
@Component
public class WorkspaceMetadataBatchWorker {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceMetadataBatchWorker.class);
  private static final int MAXIMUM_ATTEMPTS = 3;

  private final WorkspaceMetadataBatchClaimStore claims;
  private final WorkspaceMetadataBatchOperationApplicationService service;

  public WorkspaceMetadataBatchWorker(
      WorkspaceMetadataBatchClaimStore claims,
      WorkspaceMetadataBatchOperationApplicationService service) {
    this.claims = claims;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${workspace.metadata-batch.dispatch-interval-ms:250}")
  public void dispatch() {
    var now = Instant.now();
    claims.failExpired(now);
    for (var batchItemId : claims.claimReady(now)) {
      try {
        service.executeClaimed(batchItemId);
      } catch (RuntimeException exception) {
        var failureCode = stableFailureCode(exception);
        log.warn("Workspace metadata batch item {} failed with {}", batchItemId, failureCode);
        claims.retryOrFail(batchItemId, failureCode, Instant.now(), MAXIMUM_ATTEMPTS);
      }
    }
  }

  private static String stableFailureCode(RuntimeException exception) {
    var message = exception.getMessage();
    if (message != null && message.matches("[A-Z0-9_:.-]{3,240}")) {
      return message;
    }
    return "METADATA_BATCH_EXECUTION_FAILED";
  }
}
