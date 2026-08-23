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
import io.browsercloud.domain.agent.AgentPolicy;
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
  void shouldRejectActionStateThatLeavesTheTaskAllowlist() {
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var service =
        new AgentNavigationCompletionService(
            mock(AgentTaskJpaRepository.class),
            mock(SessionRepository.class),
            mock(OperationRepository.class),
            mock(NodeCommandGateway.class),
            mock(AgentExecutionService.class),
            mock(AgentControlPolicyService.class),
            objectMapper);
    var task =
        new AgentTaskEntity(
            "agt_1234567890abcdef",
            "tenant-test",
            "ses_1234567890abcdef",
            "click authorized link",
            TaskState.PLANNED.name(),
            RiskClass.R1_LOW_RISK_CHANGE.name(),
            IntentDecision.ALLOWED.name(),
            null,
            AgentPolicy.BALANCED,
            "[\"example.test\"]",
            "{}",
            "[]",
            Instant.now());
    var step =
        new PlanStep(
            "step_1234567890abcd",
            ToolId.CLICK_TARGET,
            RiskClass.R1_LOW_RISK_CHANGE,
            null,
            new StepInput("#target", 7L, null, null, null, null, null, null, null, false, 1),
            "click target",
            List.of("user_goal"),
            TrustLevel.TRUSTED,
            List.of(),
            false,
            ExecutionStrategy.SEMANTIC_DOM,
            "SESSION_RUNNING",
            "STATE_VERSION_ADVANCED",
            "cap_test",
            "signed-token");
    task.startExecution(
        "op_agent_click", "executor-test", Instant.now().plusSeconds(30), Instant.now());
    task.markAsyncPending(
        0,
        step.stepId(),
        step.toolId().name(),
        3,
        "base-hash",
        Instant.now().plusSeconds(15),
        "[]",
        "executor-test",
        Instant.now().plusSeconds(30),
        Instant.now());
    var state =
        new NodeEvent.StateUpdated(
            task.getSessionId(),
            4,
            7,
            "https://outside.test/landing",
            "Outside",
            "hash",
            "COMPLETE",
            List.of());

    assertThat(service.verifyState(task, step, state)).isEqualTo("POST_ACTION_DOMAIN_NOT_ALLOWED");

    var browserErrorState =
        new NodeEvent.StateUpdated(
            task.getSessionId(),
            5,
            8,
            "chrome-error://chromewebdata/",
            "Denied",
            "error-hash",
            "COMPLETE",
            List.of());
    assertThat(service.verifyState(task, step, browserErrorState))
        .isEqualTo("POST_ACTION_DOMAIN_NOT_ALLOWED");
  }

  @Test
  void shouldRequireFreshAuthoritativeNativeDialogClosure() {
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var service =
        new AgentNavigationCompletionService(
            mock(AgentTaskJpaRepository.class),
            mock(SessionRepository.class),
            mock(OperationRepository.class),
            mock(NodeCommandGateway.class),
            mock(AgentExecutionService.class),
            mock(AgentControlPolicyService.class),
            objectMapper);
    var task =
        new AgentTaskEntity(
            "agt_1234567890abcdef",
            "tenant-test",
            "ses_1234567890abcdef",
            "handle native prompt",
            TaskState.PLANNED.name(),
            RiskClass.R2_DATA_CHANGE.name(),
            IntentDecision.ALLOWED.name(),
            null,
            AgentPolicy.BALANCED,
            "[\"example.test\"]",
            "{}",
            "[]",
            Instant.now());
    var step =
        new PlanStep(
            "step_1234567890abcd",
            ToolId.ACCEPT_DIALOG,
            RiskClass.R2_DATA_CHANGE,
            null,
            new StepInput(
                null,
                null,
                "sealed",
                "a".repeat(64),
                6,
                ActionDataClass.OTP,
                null,
                null,
                null,
                true,
                1,
                List.of(),
                true,
                null,
                null,
                "dlg_0123456789abcdef0123"),
            "accept prompt",
            List.of("user_goal"),
            TrustLevel.TRUSTED,
            List.of(),
            false,
            ExecutionStrategy.DESKTOP_INPUT,
            "COMPLETE",
            "DIALOG_CLOSED",
            "cap_test",
            "signed-token");
    task.startExecution(
        "op_agent_dialog", "executor-test", Instant.now().plusSeconds(30), Instant.now());
    task.markAsyncPending(
        0,
        step.stepId(),
        step.toolId().name(),
        3,
        "base-hash",
        Instant.now().plusSeconds(15),
        "[]",
        "executor-test",
        Instant.now().plusSeconds(30),
        Instant.now());
    var tab = new NodeEvent.BrowserTab("tab-main", "https://example.test", "Example", true);
    var dialog =
        new NodeEvent.NativeDialog(
            "dlg_0123456789abcdef0123", "tab-main", "PROMPT", "OTP", "", false);
    var stale =
        new NodeEvent.StateUpdated(
            task.getSessionId(),
            4,
            7,
            "https://example.test",
            "Example",
            List.of(tab),
            "tab-main",
            "hash-4",
            "COMPLETE",
            List.of(),
            "complete",
            0,
            true,
            "AGENT_ACCEPT_DIALOG",
            "",
            List.of(),
            List.of(dialog),
            false);
    var closed =
        new NodeEvent.StateUpdated(
            task.getSessionId(),
            5,
            7,
            "https://example.test",
            "Example",
            List.of(tab),
            "tab-main",
            "hash-5",
            "COMPLETE",
            List.of(),
            "complete",
            0,
            true,
            "AGENT_ACCEPT_DIALOG",
            "",
            List.of(),
            List.of(),
            true);

    assertThat(service.verifyState(task, step, stale))
        .isEqualTo("NATIVE_DIALOG_NOT_AUTHORITATIVELY_CLOSED");
    assertThat(service.verifyState(task, step, closed)).isNull();
  }

  @Test
  void shouldBoundNavigationVerificationReplanAndAbortAfterBudgetIsExhausted() throws Exception {
    var taskRepository = mock(AgentTaskJpaRepository.class);
    var sessionRepository = mock(SessionRepository.class);
    var operationRepository = mock(OperationRepository.class);
    var commandGateway = mock(NodeCommandGateway.class);
    var executionService = mock(AgentExecutionService.class);
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var service =
        new AgentNavigationCompletionService(
            taskRepository,
            sessionRepository,
            operationRepository,
            commandGateway,
            executionService,
            mock(AgentControlPolicyService.class),
            objectMapper);

    var step =
        new PlanStep(
            "step_1234567890abcd",
            ToolId.NAVIGATE,
            RiskClass.R1_LOW_RISK_CHANGE,
            "https://example.test/start",
            null,
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
            AgentPolicy.BALANCED,
            "[\"example.test\"]",
            objectMapper.writeValueAsString(plan),
            "[]",
            Instant.now());
    var now = Instant.now();
    task.startExecution("op_agent_navigation", "executor-test", now.plusSeconds(30), now);
    task.markAsyncPending(
        0,
        step.stepId(),
        step.toolId().name(),
        3,
        "base-hash",
        now.plusSeconds(15),
        "[]",
        "executor-test",
        now.plusSeconds(30),
        now);

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

    verify(executionService)
        .failPendingStep(
            task.getTaskId(),
            "tenant-test",
            "op_agent_navigation",
            step.stepId(),
            "NAVIGATION_REDIRECT_DOMAIN_MISMATCH_REPLAN_BUDGET_EXHAUSTED");
  }
}
