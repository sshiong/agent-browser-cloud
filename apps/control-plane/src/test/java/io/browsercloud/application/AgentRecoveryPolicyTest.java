package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.AgentTaskEntity;
import org.junit.jupiter.api.Test;

class AgentRecoveryPolicyTest {
  private final AgentTaskEntity task = mock(AgentTaskEntity.class);

  @Test
  void mapsEveryRecoveryClassFromDurableTaskEvidence() {
    assertDirective("PLANNED", "FAILED", null, null, null, "RETRY", false);
    assertDirective("RUNNING", null, null, null, "POST_ACTION_STATE_NOT_ADVANCED", "REFRESH", true);
    assertDirective("RUNNING", null, null, null, "TARGET_SEMANTICS_CHANGED", "REPLAN", true);
    assertDirective("RUNNING", null, null, "HUMAN_INPUT_PRIORITY", null, "WAIT", true);
    assertDirective("WAITING_FOR_HUMAN", null, "OTP_REQUIRED", null, null, "HUMAN", false);
    assertDirective("FAILED", null, null, null, null, "TERMINAL", false);
  }

  @Test
  void healthyOrCompletedTasksDoNotInventRecoveryWork() {
    when(task.getState()).thenReturn("RUNNING", "PLANNED", "COMPLETED");
    assertThat(AgentRecoveryPolicy.guidance(task)).isNull();
    assertThat(AgentRecoveryPolicy.guidance(task)).isNull();
    assertThat(AgentRecoveryPolicy.guidance(task)).isNull();
  }

  @Test
  void promptInjectionBlockIsTerminalAndGenericBlockRequiresHuman() {
    when(task.getState()).thenReturn("BLOCKED");
    when(task.getBlockedReason())
        .thenReturn("PROMPT_INJECTION_SOURCE_FORBIDDEN", "APPROVAL_REQUIRED");
    assertThat(AgentRecoveryPolicy.guidance(task).directive()).isEqualTo("TERMINAL");
    assertThat(AgentRecoveryPolicy.guidance(task).directive()).isEqualTo("HUMAN");
  }

  @Test
  void boundsExternallyVisibleReasonCodes() {
    when(task.getState()).thenReturn("FAILED");
    when(task.getLastError()).thenReturn("X".repeat(300));

    assertThat(AgentRecoveryPolicy.guidance(task).reasonCode()).hasSize(256);
  }

  private void assertDirective(
      String state,
      String reviewerStatus,
      String blockedReason,
      String executionWait,
      String replanReason,
      String directive,
      boolean automatic) {
    when(task.getState()).thenReturn(state);
    when(task.getReviewerStatus()).thenReturn(reviewerStatus);
    when(task.getReviewerFailureCode()).thenReturn("MODEL_PROVIDER_UNAVAILABLE");
    when(task.getBlockedReason()).thenReturn(blockedReason);
    when(task.getExecutionWaitReason()).thenReturn(executionWait);
    when(task.getReplanReason()).thenReturn(replanReason);
    var guidance = AgentRecoveryPolicy.guidance(task);
    assertThat(guidance.directive()).isEqualTo(directive);
    assertThat(guidance.automatic()).isEqualTo(automatic);
    assertThat(guidance.reasonCode()).isNotBlank();
  }
}
