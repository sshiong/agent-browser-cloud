package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OwnerType;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentBatchOutcomeVerificationTest {
  private String verifyOutcome(String status, String errorCode) {
    var service =
        new AgentNavigationCompletionService(
            mock(AgentTaskJpaRepository.class),
            mock(SessionRepository.class),
            mock(OperationRepository.class),
            mock(NodeCommandGateway.class),
            mock(AgentExecutionService.class),
            mock(AgentControlPolicyService.class),
            new ObjectMapper().findAndRegisterModules());
    var task = mock(AgentTaskEntity.class);
    when(task.getPendingStateVersion()).thenReturn(3L);
    when(task.getAllowedDomains()).thenReturn("[\"example.test\"]");
    var step = mock(PlanStep.class);
    var input = mock(StepInput.class);
    var action = mock(ActionInput.class);
    when(step.toolId()).thenReturn(ToolId.EXECUTE_ACTIONS);
    when(step.input()).thenReturn(input);
    when(input.actions()).thenReturn(List.of(action));
    when(action.actionId()).thenReturn("action_1");
    when(action.toolId()).thenReturn(ToolId.CLICK_TARGET);
    var state = mock(NodeEvent.StateUpdated.class);
    when(state.stateVersion()).thenReturn(4L);
    when(state.stateQuality()).thenReturn("COMPLETE");
    when(state.url()).thenReturn("https://example.test/form");
    when(state.actionOutcomes())
        .thenReturn(List.of(new NodeEvent.AgentActionOutcome("action_1", status, errorCode, 4, 2)));
    return service.verifyState(task, step, state);
  }

  @Test
  void newStateAndMatchingActionIdsCannotTurnFailuresIntoSuccess() {
    for (var status : new String[] {"FAILED", "SKIPPED", "UNKNOWN", ""}) {
      assertThat(verifyOutcome(status, "")).isEqualTo("BATCH_ACTION_FAILED");
    }
  }

  @Test
  void contradictorySuccessWithErrorFailsClosed() {
    assertThat(verifyOutcome("SUCCEEDED", "ACTION_PRECONDITION_FAILED"))
        .isEqualTo("BATCH_ACTION_FAILED");
  }

  @Test
  void completeSuccessfulPrimitiveEvidencePasses() {
    assertThat(verifyOutcome("SUCCEEDED", "")).isNull();
  }

  @Test
  void knownFailureIsTerminalForThisAttemptAndCannotBecomeSuccessAfterResync() throws Exception {
    var tasks = mock(AgentTaskJpaRepository.class);
    var operations = mock(OperationRepository.class);
    var commands = mock(NodeCommandGateway.class);
    var execution = mock(AgentExecutionService.class);
    var mapper = mock(ObjectMapper.class);
    var service =
        spy(
            new AgentNavigationCompletionService(
                tasks,
                mock(SessionRepository.class),
                operations,
                commands,
                execution,
                mock(AgentControlPolicyService.class),
                mapper));
    var task = mock(AgentTaskEntity.class);
    var step = mock(PlanStep.class);
    var plan = mock(AgentPlan.class);
    var operation = mock(ExclusiveOperation.class);
    var event = mock(NodeEventReceived.class);
    var state = mock(NodeEvent.StateUpdated.class);
    when(event.operationEpoch()).thenReturn(1L);
    when(event.sessionId()).thenReturn("session");
    when(event.tenantId()).thenReturn("tenant");
    when(operation.operationEpoch()).thenReturn(1L);
    when(operation.operationId()).thenReturn("operation");
    when(operation.actorId()).thenReturn("task");
    when(operation.mode()).thenReturn(OperationMode.AGENT_INTERACTIVE);
    when(operation.ownerType()).thenReturn(OwnerType.AGENT);
    when(operations.findActive("session")).thenReturn(Optional.of(operation));
    when(tasks.findForUpdate("task", "tenant")).thenReturn(Optional.of(task));
    when(task.getTaskId()).thenReturn("task");
    when(task.getState()).thenReturn("RUNNING");
    when(task.getOperationId()).thenReturn("operation");
    when(task.getPendingStepId()).thenReturn("step");
    when(task.getPendingToolId()).thenReturn("EXECUTE_ACTIONS");
    when(task.getPlan()).thenReturn("plan");
    when(mapper.readValue("plan", AgentPlan.class)).thenReturn(plan);
    when(plan.steps()).thenReturn(List.of(step));
    when(step.stepId()).thenReturn("step");
    when(step.toolId()).thenReturn(ToolId.EXECUTE_ACTIONS);
    doReturn("BATCH_ACTION_FAILED").when(service).verifyState(task, step, state);

    service.stateUpdated(event, state);

    verify(execution).failPendingStep("task", "tenant", "operation", "step", "BATCH_ACTION_FAILED");
    verifyNoMoreInteractions(execution);
    verifyNoInteractions(commands);
  }
}
