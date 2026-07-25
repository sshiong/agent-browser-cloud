package io.browsercloud.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.browsercloud.coordinator.exceptions.ActiveOperationExistsException;
import io.browsercloud.domain.operation.*;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionCoordinatorTest {

  @Mock private SessionRepository sessionRepository;

  @Mock private OperationRepository operationRepository;

  @Mock private NodeCommandGateway nodeCommandGateway;

  @Mock private OutboxPublisher outboxPublisher;

  private SessionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    coordinator =
        new SessionCoordinator(
            sessionRepository, operationRepository, nodeCommandGateway, outboxPublisher);
  }

  @Test
  void shouldCreateStartOperation() {
    // Given
    var session = createSession("ses-1", SessionState.CREATED);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(1L);

    // When
    var result = coordinator.handle(new StartSession("ses-1", "runtime-1", "idem-1"));

    // Then
    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    verify(operationRepository).insert(any(ExclusiveOperation.class));
    verify(nodeCommandGateway).send(any(NodeCommand.class));
  }

  @Test
  void shouldRejectSecondActiveOperation() {
    // Given
    var session = createSession("ses-1", SessionState.CREATED);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    doThrow(new ActiveOperationExistsException("ses-1", "op-1"))
        .when(operationRepository)
        .ensureNoActiveOperation("ses-1");

    // When & Then
    assertThatThrownBy(() -> coordinator.handle(new StartSession("ses-1", "runtime-1", "idem-1")))
        .isInstanceOf(ActiveOperationExistsException.class);
  }

  @Test
  void shouldHandleRuntimeStartedEvent() {
    // Given
    var session = createSession("ses-1", SessionState.STARTING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);

    var event =
        new NodeEvent.RuntimeStarted(
            "ses-1", "node-1", "runtime-1", 12345L, 1L, "http://localhost:9222");
    var operation = createActiveOperation("ses-1");
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.of(operation));

    // When
    var result = coordinator.handle(nodeEvent(event, 0, 1));

    // Then
    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository).updateWithExpectedEpoch(any(), eq(0L));
    verify(operationRepository)
        .transition(eq("op-1"), eq(OperationState.ACTIVE), eq(OperationState.COMMITTED));
  }

  @Test
  void shouldHandleRuntimeCrashedEvent() {
    // Given
    var session = createSession("ses-1", SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(2L);

    var event = new NodeEvent.RuntimeCrashed("ses-1", "RENDERER_CRASH", "OOM");

    // When
    var result = coordinator.handle(nodeEvent(event, 0, 0));

    // Then
    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(context -> context.state() == SessionState.RECOVERING), eq(0L));
    verify(operationRepository)
        .insert(argThat(operation -> operation.mode() == OperationMode.RECOVERY));
    verify(nodeCommandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("StartRuntime") && command.operationEpoch() == 2));
    verify(outboxPublisher)
        .append(
            argThat(
                domainEvent ->
                    domainEvent instanceof SessionStateChanged changed
                        && changed.newState() == SessionState.RECOVERING));
  }

  @Test
  void shouldOpenRecoveryCircuitAfterThreeAttemptsInOneHour() {
    var session = createSession("ses-1", SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.countSince(eq("ses-1"), eq(OperationMode.RECOVERY), any()))
        .thenReturn(3L);

    var result =
        coordinator.handle(
            nodeEvent(
                new NodeEvent.RuntimeCrashed("ses-1", "BROWSER_CRASH", "repeated exit"), 0, 0));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(context -> context.state() == SessionState.FAILED), eq(0L));
    verify(nodeCommandGateway, never()).send(any());
    verify(operationRepository, never()).insert(any());
  }

  @Test
  void shouldCommitRecoveryWhenReplacementRuntimeStarts() {
    var session = createSession("ses-1", SessionState.RECOVERING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(Optional.of(createActiveOperation("ses-1", OperationMode.RECOVERY)));

    var result =
        coordinator.handle(
            nodeEvent(
                new NodeEvent.RuntimeStarted(
                    "ses-1", "node-1", "runtime-1", 54321, 2, "http://localhost:9223"),
                0,
                1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(
                context ->
                    context.state() == SessionState.RUNNING
                        && context.contextEpoch() == 1
                        && context.browserGeneration() == 2),
            eq(0L));
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.COMMITTED);
  }

  @Test
  void shouldHandleRuntimeStoppedEvent() {
    var session = createSession("ses-1", SessionState.TERMINATING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(Optional.of(createActiveOperation("ses-1")));

    var result =
        coordinator.handle(
            nodeEvent(new NodeEvent.RuntimeStopped("ses-1", "user_request", 0), 0, 1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(context -> context.state() == SessionState.TERMINATED), eq(0L));
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.COMMITTED);
    verify(outboxPublisher)
        .append(
            argThat(
                event ->
                    event instanceof SessionStateChanged changed
                        && changed.newState() == SessionState.TERMINATED));
  }

  @Test
  void shouldRejectStaleContextEpochBeforeApplyingNodeEvent() {
    var session = createSession("ses-1", SessionState.STARTING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    var event =
        new NodeEvent.RuntimeStarted(
            "ses-1", "node-1", "runtime-1", 12345L, 1L, "http://localhost:9222");

    var result = coordinator.handle(nodeEvent(event, 1, 1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.REJECTED);
    assertThat(result.reason()).isEqualTo("STALE_CONTEXT_EPOCH");
    verify(sessionRepository, never()).updateWithExpectedEpoch(any(), anyLong());
  }

  @Test
  void shouldRejectStaleOperationEpochBeforeApplyingNodeEvent() {
    var session = createSession("ses-1", SessionState.STARTING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(Optional.of(createActiveOperation("ses-1")));
    var event =
        new NodeEvent.RuntimeStarted(
            "ses-1", "node-1", "runtime-1", 12345L, 1L, "http://localhost:9222");

    var result = coordinator.handle(nodeEvent(event, 0, 2));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.REJECTED);
    assertThat(result.reason()).isEqualTo("STALE_OPERATION_EPOCH");
    verify(sessionRepository, never()).updateWithExpectedEpoch(any(), anyLong());
  }

  @Test
  void shouldHandleTimeout() {
    // Given
    var operation = createActiveOperation("ses-1");

    // When
    var result = coordinator.handle(new OperationTimedOut("ses-1", "op-1"));

    // Then
    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.TIMED_OUT);
    verify(outboxPublisher).append(any(OperationTimedOutEvent.class));
  }

  private SessionContext createSession(String sessionId, SessionState state) {
    return new SessionContext(
        sessionId,
        "tenant-1",
        "profile-1",
        null,
        state == SessionState.CREATED ? null : "runtime-1",
        null,
        null,
        0,
        0,
        0,
        0,
        ResourceClass.L2,
        state,
        "",
        Instant.now(),
        Instant.now());
  }

  private ExclusiveOperation createActiveOperation(String sessionId) {
    return createActiveOperation(sessionId, OperationMode.AGENT_INTERACTIVE);
  }

  private ExclusiveOperation createActiveOperation(String sessionId, OperationMode mode) {
    return new ExclusiveOperation(
        "op-1",
        sessionId,
        OwnerType.SYSTEM,
        "control-plane",
        mode,
        0,
        0,
        0,
        1,
        null,
        true,
        true,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of(),
        Instant.now().plusSeconds(300),
        Instant.now(),
        null);
  }

  private NodeEventReceived nodeEvent(NodeEvent event, long contextEpoch, long operationEpoch) {
    return new NodeEventReceived(
        "evt-test", "tenant-1", "ses-1", 0, contextEpoch, operationEpoch, 1, event);
  }
}
