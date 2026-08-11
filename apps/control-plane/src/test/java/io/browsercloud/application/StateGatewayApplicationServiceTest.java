package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.browsercloud.api.StateResyncRequest;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StateGatewayApplicationServiceTest {

  @Mock private SessionRepository sessionRepository;
  @Mock private BrowserStateRepository stateRepository;
  @Mock private NodeCommandGateway nodeCommandGateway;
  @Mock private IdempotencyService idempotencyService;
  @Mock private StateResyncAdmissionService admissionService;
  @Mock private AuditApplicationService auditService;

  private StateGatewayApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new StateGatewayApplicationService(
            sessionRepository,
            stateRepository,
            nodeCommandGateway,
            idempotencyService,
            admissionService,
            auditService);
  }

  @Test
  void shouldMarkStateResyncingAndQueueVersionedNodeCommand() {
    var session = session(SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses_1234567890abcdef")).thenReturn(session);
    when(idempotencyService.claimStateResync(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(4));

    var response =
        service.requestResync(
            session.sessionId(),
            session.tenantId(),
            "operator-test",
            new StateResyncRequest(StateResyncRequest.Mode.REGION, "#app", "TEST"),
            "idem-resync-1");

    verify(stateRepository)
        .markResyncing("tenant-test", session.contextEpoch(), session.sessionId());
    var command = ArgumentCaptor.forClass(NodeCommand.class);
    verify(nodeCommandGateway).send(command.capture());
    assertThat(command.getValue().commandType()).isEqualTo("RequestStateResync");
    assertThat(command.getValue().operationEpoch()).isZero();
    assertThat(command.getValue().idempotencyKey()).isEqualTo("idem-resync-1");
    assertThat(response.requestId()).isEqualTo(command.getValue().messageId());
    assertThat(response.state()).isEqualTo("QUEUED");
    verify(admissionService)
        .admitUser(
            session.tenantId(),
            session.sessionId(),
            "operator-test",
            command.getValue().messageId(),
            StateResyncRequest.Mode.REGION,
            "#app",
            "TEST");
  }

  @Test
  void idempotentReplayDoesNotConsumeAnotherResyncBudget() {
    var session = session(SessionState.RUNNING);
    when(sessionRepository.requireForUpdate(session.sessionId())).thenReturn(session);
    when(idempotencyService.claimStateResync(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn("cmd_existing_1234567890");

    var response =
        service.requestResync(
            session.sessionId(),
            session.tenantId(),
            "operator-test",
            new StateResyncRequest(StateResyncRequest.Mode.FULL, null, "TEST"),
            "idem-resync-replay");

    assertThat(response.requestId()).isEqualTo("cmd_existing_1234567890");
    verifyNoInteractions(admissionService, stateRepository, nodeCommandGateway);
  }

  @Test
  void workflowOwnedFullResyncConsumesTheAutomaticCircuitBudget() {
    var session = session(SessionState.RUNNING);
    when(sessionRepository.requireForUpdate(session.sessionId())).thenReturn(session);
    when(idempotencyService.claimStateResync(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(4));

    var response =
        service.requestResync(
            session.sessionId(),
            session.tenantId(),
            new StateResyncRequest(
                StateResyncRequest.Mode.FULL, null, "MIGRATION_BUSINESS_RECOVERY"),
            "migration-test-resync");

    verify(admissionService)
        .admitAutomatic(
            session.tenantId(),
            session.sessionId(),
            "state-resync-workflow",
            response.requestId(),
            "MIGRATION_BUSINESS_RECOVERY");
    verify(auditService)
        .append(
            org.mockito.ArgumentMatchers.argThat(
                record ->
                    record.eventType().equals("STATE_RESYNC_REQUESTED")
                        && record.actorType().equals("SYSTEM")));
  }

  @Test
  void shouldRejectRegionResyncWithoutRoot() {
    var session = session(SessionState.RUNNING);
    when(sessionRepository.requireForUpdate(session.sessionId())).thenReturn(session);

    assertThatThrownBy(
            () ->
                service.requestResync(
                    session.sessionId(),
                    session.tenantId(),
                    "operator-test",
                    new StateResyncRequest(StateResyncRequest.Mode.REGION, "", "TEST"),
                    "idem-resync-2"))
        .isInstanceOf(StateGatewayApplicationService.InvalidStateResyncRequestException.class);
  }

  private static SessionContext session(SessionState state) {
    var now = Instant.parse("2026-07-26T00:00:00Z");
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-test",
        null,
        "pxy-test",
        1,
        4,
        2,
        1,
        ResourceClass.L2,
        state,
        "",
        now,
        now);
  }
}
