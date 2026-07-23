package io.browsercloud.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkflowStateMachineTest {

  @Test
  void shouldAllowPendingToDispatched() {
    WorkflowStateMachine.assertTransitionAllowed(WorkflowState.PENDING, WorkflowState.DISPATCHED);
  }

  @Test
  void shouldAllowPendingToCancelled() {
    WorkflowStateMachine.assertTransitionAllowed(WorkflowState.PENDING, WorkflowState.CANCELLED);
  }

  @Test
  void shouldRejectPendingToRunning() {
    assertThatThrownBy(
            () ->
                WorkflowStateMachine.assertTransitionAllowed(
                    WorkflowState.PENDING, WorkflowState.RUNNING))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldAllowRunningToCompleting() {
    WorkflowStateMachine.assertTransitionAllowed(WorkflowState.RUNNING, WorkflowState.COMPLETING);
  }

  @Test
  void shouldAllowRunningToFailed() {
    WorkflowStateMachine.assertTransitionAllowed(WorkflowState.RUNNING, WorkflowState.FAILED);
  }

  @Test
  void shouldRejectCompletedToAny() {
    assertThatThrownBy(
            () ->
                WorkflowStateMachine.assertTransitionAllowed(
                    WorkflowState.COMPLETED, WorkflowState.RUNNING))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldCheckTransitionAllowed() {
    assertThat(
            WorkflowStateMachine.isTransitionAllowed(
                WorkflowState.PENDING, WorkflowState.DISPATCHED))
        .isTrue();
    assertThat(
            WorkflowStateMachine.isTransitionAllowed(WorkflowState.PENDING, WorkflowState.RUNNING))
        .isFalse();
    assertThat(
            WorkflowStateMachine.isTransitionAllowed(
                WorkflowState.COMPLETED, WorkflowState.RUNNING))
        .isFalse();
  }
}
