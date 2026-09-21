package io.browsercloud.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded internal worker; PostgreSQL queue and Storage Helper tombstones make retries durable. */
@Component
public class RecordingRetentionDeletionWorker {

  private final RecordingRetentionDeletionApplicationService deletions;
  private final int batchSize;

  public RecordingRetentionDeletionWorker(
      RecordingRetentionDeletionApplicationService deletions,
      @Value("${recording.retention-deletion.batch-size:10}") int batchSize) {
    if (batchSize < 1 || batchSize > 100) {
      throw new IllegalStateException("Recording retention deletion batch size must be 1..100");
    }
    this.deletions = deletions;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${recording.retention-deletion.interval-ms:30000}")
  public void runOnce() {
    deletions.enqueueDue(batchSize);
    for (var index = 0; index < batchSize && deletions.processNext(); index++) {
      // Each call owns one independent transaction and exact Recording row lock.
    }
  }
}
