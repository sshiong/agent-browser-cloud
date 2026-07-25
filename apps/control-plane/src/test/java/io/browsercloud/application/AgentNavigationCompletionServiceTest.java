package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.operation.OwnerType;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentNavigationCompletionServiceTest {

  @Test
  void shouldBoundNavigationVerificationReplanAndAbortAfterBudgetIsExhausted() throws Exception {
    var taskRepository = mock(AgentTaskJpaRepository.class);
    var sessionRepository = mock(SessionRepository.class);
    var operationRepository = mock(OperationRepository.class);
    var commandGateway = mock(NodeCommandGateway.class);
    var readToolService = mock(AgentReadToolService.class);
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var service =
        new AgentNavigationCompletionService(
            taskRepository,
            sessionRepository,
            operationRepository,
            commandGateway,
            readToolService,
            objectMapper);

    var step =
        new PlanStep(
            "step_1234567890abcd",
            ToolId.NAVIGATE,
            RiskClass.R1_LOW_RISK_CHANGE,
            "https://example.test/start",
            "navigate",
            List.of("user_goal"),
            TrustLevel.TRUSTED,
            List.of(),
            false,
            ExecutionStrategy.SEMANTIC_DOM,
            "SESSION_RUNNING",
            "URL_HOST_EQUALS_ALLOWED_DOMAIN",
            "cap_test",
            "signed-token");
    var plan =
        new AgentPlan("int_1234567890abcdef", List.of(step), 4, 1, Instant.now().plusSeconds(300));
    var task =
        new AgentTaskEntity(
            "agt_1234567890abcdef",
            "tenant-test",
            "ses_1234567890abcdef",
            "navigate",
            TaskState.PLANNED.name(),
            RiskClass.R1_LOW_RISK_CHANGE.name(),
            IntentDecision.ALLOWED.name(),
            null,
            "[\"example.test\"]",
            objectMapper.writeValueAsString(plan),
            "[]",
            Instant.now());
    task.startExecution("op_agent_navigation", Instant.now());
    task.markNavigationPending(3, Instant.now());

    var operation =
        new ExclusiveOperation(
            "op_agent_navigation",
            "ses_1234567890abcdef",
            OwnerType.AGENT,
            task.getTaskId(),
            OperationMode.AGENT_INTERACTIVE,
            40,
            2,
            4,
            5,
            null,
            true,
            true,
            OperationPhase.EXECUTING,
            OperationState.ACTIVE,
            Set.of(ToolId.NAVIGATE.name()),
            Instant.now().plusSeconds(120),
            Instant.now(),
            null);
    var session = mock(SessionContext.class);
    when(session.sessionId()).thenReturn("ses_1234567890abcdef");
    when(session.tenantId()).thenReturn("tenant-test");
    when(session.coordinatorTerm()).thenReturn(2L);
    when(session.contextEpoch()).thenReturn(4L);
    when(operationRepository.findActive("ses_1234567890abcdef")).thenReturn(Optional.of(operation));
    when(taskRepository.findForUpdate(task.getTaskId(), "tenant-test"))
        .thenReturn(Optional.of(task));
    when(sessionRepository.require("ses_1234567890abcdef")).thenReturn(session);
    var event =
        new NodeEventReceived(
            "evt_navigation",
            "tenant-test",
            "ses_1234567890abcdef",
            2,
            4,
            5,
            7,
            new NodeEvent.StateUpdated(
                "ses_1234567890abcdef",
                4,
                2,
                "https://redirect.test/landing",
                "Redirect",
                "hash",
                "COMPLETE",
                List.of()));

    service.stateUpdated(event, (NodeEvent.StateUpdated) event.event());

    assertThat(task.getReplanCount()).isEqualTo(1);
    assertThat(task.getState()).isEqualTo(TaskState.RUNNING.name());
    verify(commandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("RequestStateResync")
                        && command.operationEpoch() == 5));

    service.stateUpdated(event, (NodeEvent.StateUpdated) event.event());

    assertThat(task.getState()).isEqualTo(TaskState.FAILED.name());
    assertThat(task.getLastError())
        .isEqualTo("NAVIGATION_REDIRECT_DOMAIN_MISMATCH_REPLAN_BUDGET_EXHAUSTED");
    verify(operationRepository)
        .transition("op_agent_navigation", OperationState.ACTIVE, OperationState.ABORTED);
    verifyNoInteractions(readToolService);
  }
}
