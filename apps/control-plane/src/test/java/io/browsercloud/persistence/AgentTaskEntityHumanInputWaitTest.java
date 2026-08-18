package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.browsercloud.domain.agent.AgentPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentTaskEntityHumanInputWaitTest {

  @Test
  void projectsHumanInputWaitWithoutChangingRunningLifecycle() {
    var createdAt = Instant.parse("2026-08-11T08:00:00Z");
    var task =
        new AgentTaskEntity(
            "agt_1234567890abcdef",
            "tenant-test",
            "ses_1234567890abcdef",
            "Inspect current page",
            "PLANNED",
            "R0_READ_ONLY",
            "ALLOWED",
            null,
            AgentPolicy.BALANCED,
            "[]",
            "{}",
            "[]",
            createdAt);
    task.startExecution(
        "op_1234567890abcdef", "worker-test", createdAt.plusSeconds(30), createdAt.plusSeconds(1));

    var waitSince = createdAt.plusSeconds(2);
    assertThat(task.deferForHumanInput(waitSince)).isTrue();
    assertThat(task.getState()).isEqualTo("RUNNING");
    assertThat(task.getExecutionWaitReason()).isEqualTo("HUMAN_INPUT_PRIORITY");
    assertThat(task.getExecutionWaitSince()).isEqualTo(waitSince);
    assertThat(task.deferForHumanInput(waitSince.plusSeconds(1))).isFalse();
    assertThat(task.getExecutionWaitSince()).isEqualTo(waitSince);

    assertThat(task.resumeAfterHumanInput(waitSince.plusSeconds(2))).isTrue();
    assertThat(task.getState()).isEqualTo("RUNNING");
    assertThat(task.getExecutionWaitReason()).isNull();
    assertThat(task.getExecutionWaitSince()).isNull();
  }

  @Test
  void rebindsAWaitingAgentToTheNextChallengeWithoutResumingIt() {
    var createdAt = Instant.parse("2026-08-11T08:00:00Z");
    var task =
        new AgentTaskEntity(
            "agt_1234567890abcdef",
            "tenant-test",
            "ses_1234567890abcdef",
            "Inspect current page",
            "PLANNED",
            "R0_READ_ONLY",
            "ALLOWED",
            null,
            AgentPolicy.BALANCED,
            "[]",
            "{}",
            "[]",
            createdAt);
    task.startExecution(
        "op_1234567890abcdef", "worker-test", createdAt.plusSeconds(30), createdAt.plusSeconds(1));
    task.awaitChallenge(1, "[]", "chl_1234567890abcdefghij", createdAt.plusSeconds(2));

    task.rebindChallenge("chl_abcdefghijklmnopqrst", createdAt.plusSeconds(3));

    assertThat(task.getState()).isEqualTo("WAITING_FOR_HUMAN");
    assertThat(task.getChallengeEventId()).isEqualTo("chl_abcdefghijklmnopqrst");
    assertThat(task.getBlockedReason()).isEqualTo("CHALLENGE_DETECTED");
  }

  @Test
  void requestsHumanAssistanceOnceWithoutFailingOrForcingTakeover() {
    var createdAt = Instant.parse("2026-08-11T08:00:00Z");
    var task =
        new AgentTaskEntity(
            "agt_1234567890abcdef",
            "tenant-test",
            "ses_1234567890abcdef",
            "Sign in",
            "PLANNED",
            "R0_READ_ONLY",
            "ALLOWED",
            null,
            AgentPolicy.BALANCED,
            "[]",
            "{}",
            "[]",
            createdAt);
    task.startExecution(
        "op_1234567890abcdef", "worker-test", createdAt.plusSeconds(30), createdAt.plusSeconds(1));
    task.awaitChallenge(1, "[]", "chl_1234567890abcdefghij", createdAt.plusSeconds(2));

    assertThat(task.requestHumanAssistance("OTP_REQUIRED", createdAt.plusSeconds(3))).isTrue();
    assertThat(task.requestHumanAssistance("OTP_REQUIRED", createdAt.plusSeconds(4))).isFalse();
    assertThat(task.getState()).isEqualTo("WAITING_FOR_HUMAN");
    assertThat(task.getChallengeEventId()).isEqualTo("chl_1234567890abcdefghij");
    assertThat(task.getBlockedReason()).isEqualTo("HUMAN_ASSISTANCE_REQUIRED:OTP_REQUIRED");
  }
}
