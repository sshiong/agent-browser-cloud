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
    when(sessionRepository.require("ses-1")).thenReturn(session);

    var event =
        new NodeEvent.RuntimeStarted(
            "ses-1", "node-1", "runtime-1", 12345L, 1L, "http://localhost:9222");
    var operation = createActiveOperation("ses-1");
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.of(operation));

    // When
    var result = coordinator.handle(new NodeEventReceived("ses-1", event));

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
    when(sessionRepository.require("ses-1")).thenReturn(session);

    var event = new NodeEvent.RuntimeCrashed("ses-1", "RENDERER_CRASH", "OOM");

    // When
    var result = coordinator.handle(new NodeEventReceived("ses-1", event));

    // Then
    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository).updateWithExpectedEpoch(any(), eq(0L));
    verify(outboxPublisher).append(any(SessionStateChanged.class));
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
        null,
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
    return new ExclusiveOperation(
        "op-1",
        sessionId,
        OwnerType.SYSTEM,
        "control-plane",
        OperationMode.AGENT_INTERACTIVE,
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
}
