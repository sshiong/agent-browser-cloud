package io.browsercloud.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Small bounded batches; committed PostgreSQL requests are the only source of work. */
@Component
public class RecordingLegalHoldWorker {
  private final RecordingLegalHoldApplicationService holds;

  public RecordingLegalHoldWorker(RecordingLegalHoldApplicationService holds) {
    this.holds = holds;
  }

  @Scheduled(fixedDelayString = "${recording.cloud-hold.interval-ms:30000}")
  public void runOnce() {
    for (var index = 0; index < 10 && holds.processNext(); index++) {
      // Each request has independent durable authorization and execution transactions.
    }
  }
}
