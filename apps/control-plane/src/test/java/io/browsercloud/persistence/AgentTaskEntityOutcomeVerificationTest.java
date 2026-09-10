package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.browsercloud.domain.agent.AgentPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentTaskEntityOutcomeVerificationTest {

  @Test
  void actionCompletionWaitsForIndependentVerifiedOutcome() {
    var task = runningTask();
    var now = Instant.parse("2026-09-09T08:00:02Z");

    task.awaitOutcomeVerification(2, "[]", "out_1234567890abcdefghij", "a".repeat(64), now);

    assertThat(task.getState()).isEqualTo("VERIFYING_OUTCOME");
    assertThat(task.getOutcomeVerificationStatus()).isEqualTo("QUEUED");
    assertThat(task.getExecutionCompletedAt()).isNull();

    task.markOutcomeVerifierRunning(now.plusSeconds(1));
    task.recordOutcomeAccounting("deployment", "model", "revision", 12, 4, 9, 25);
    task.verifyOutcome("[\"GOAL_SATISFIED\"]", now.plusSeconds(2));

    assertThat(task.getState()).isEqualTo("COMPLETED");
    assertThat(task.getOutcomeVerificationStatus()).isEqualTo("VERIFIED");
    assertThat(task.getOutcomeDecision()).isEqualTo("VERIFIED");
    assertThat(task.getOutcomeCompletedAt()).isEqualTo(now.plusSeconds(2));
    assertThat(task.getExecutionCompletedAt()).isEqualTo(now.plusSeconds(2));
  }

  @Test
  void technicalSuccessCannotHideRejectedBusinessOutcome() {
    var task = runningTask();
    var now = Instant.parse("2026-09-09T08:00:02Z");
    task.awaitOutcomeVerification(2, "[]", "out_1234567890abcdefghij", "b".repeat(64), now);

    task.rejectOutcome("[\"BUSINESS_ERROR_VISIBLE\"]", now.plusSeconds(1));

    assertThat(task.getState()).isEqualTo("FAILED");
    assertThat(task.getOutcomeVerificationStatus()).isEqualTo("NOT_VERIFIED");
    assertThat(task.getOutcomeDecision()).isEqualTo("NOT_VERIFIED");
    assertThat(task.getLastError()).isEqualTo("AGENT_OUTCOME_NOT_VERIFIED");
  }

  private static AgentTaskEntity runningTask() {
    var createdAt = Instant.parse("2026-09-09T08:00:00Z");
    var task =
        new AgentTaskEntity(
            "agt_1234567890abcdef",
            "tenant-test",
            "ses_1234567890abcdef",
            "Save settings",
            "PLANNED",
            "R2_DATA_CHANGE",
            "ALLOWED",
            null,
            AgentPolicy.BALANCED,
            "[\"example.test\"]",
            "{}",
            "[]",
            createdAt);
    task.startExecution(
        "op_1234567890abcdef", "worker-test", createdAt.plusSeconds(30), createdAt.plusSeconds(1));
    return task;
  }
}
