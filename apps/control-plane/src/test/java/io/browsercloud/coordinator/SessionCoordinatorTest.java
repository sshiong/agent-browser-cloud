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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

  @Mock private CoordinatorOwnershipService ownershipService;

  @Mock private RuntimeResourceLimitsRepository resourceLimitsRepository;
  @Mock private CoordinatorRouteAuthority routeAuthority;

  private SessionCoordinator coordinator;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    coordinator =
        new SessionCoordinator(
            sessionRepository,
            operationRepository,
            nodeCommandGateway,
            outboxPublisher,
            ownershipService,
            new CoordinatorReconciliationMetrics(meterRegistry),
            resourceLimitsRepository,
            routeAuthority);
    lenient()
        .when(routeAuthority.resolve(anyString()))
        .thenAnswer(
            invocation ->
                new CoordinatorRouteAuthority.SessionRoute(
                    invocation.getArgument(0), "tenant-test", 1, 0, 0));
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
  void locksSessionBeforeCoordinatorOwnershipToMatchMigrationLockOrder() {
    when(ownershipService.acquireSession("ses-1", 1)).thenReturn(1L);
    when(sessionRepository.requireForUpdate("ses-1"))
        .thenReturn(createSession("ses-1", SessionState.RUNNING));
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.empty());

    var result = coordinator.handle(new ReconcileAgentExecution("ses-1", "task-1"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    var ordered = inOrder(routeAuthority, sessionRepository, ownershipService);
    ordered.verify(routeAuthority).resolve("ses-1");
    ordered.verify(sessionRepository).lockForUpdate("ses-1");
    ordered.verify(ownershipService).acquireSession("ses-1", 1);
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
            "ses-1", "node-1", "runtime-1", 12345L, 1L, "http://localhost:9222", "", "", "", "");
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
                    "ses-1",
                    "node-1",
                    "runtime-1",
                    54321,
                    2,
                    "http://localhost:9223",
                    "",
                    "",
                    "",
                    ""),
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
  void shouldAcquireHumanTakeoverAndCreateInputBarrierCommand() {
    var session = createSession("ses-1", SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(4L);

    var result = coordinator.handle(new RequestHumanTakeover("ses-1", "user-1"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    verify(operationRepository)
        .insert(
            argThat(
                operation ->
                    operation.mode() == OperationMode.HUMAN_TAKEOVER
                        && operation.actorId().equals("user-1")
                        && operation.operationEpoch() == 4));
    verify(nodeCommandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("BeginHumanTakeover")
                        && command.operationEpoch() == 4));
  }

  @Test
  void shouldMoveHumanTakeoverToExecutingAfterInputBarrierAndStateResync() {
    var session = createSession("ses-1", SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(
            Optional.of(
                createActiveOperation(
                    "ses-1", OperationMode.HUMAN_TAKEOVER, OperationPhase.PREPARING, "user-1")));
    var state =
        new NodeEvent.StateUpdated(
            "ses-1",
            3,
            3,
            "https://example.test",
            "Example",
            "hash",
            "COMPLETE",
            java.util.List.of());

    var result =
        coordinator.handle(
            nodeEvent(new NodeEvent.HumanTakeoverReady("ses-1", "user-1", state), 0, 1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(operationRepository)
        .transitionPhase("op-1", OperationPhase.PREPARING, OperationPhase.EXECUTING);
  }

  @Test
  void shouldReleaseHumanTakeoverThroughNodeBarrier() {
    var session = createSession("ses-1", SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(
            Optional.of(
                createActiveOperation(
                    "ses-1", OperationMode.HUMAN_TAKEOVER, OperationPhase.EXECUTING, "user-1")));

    var result = coordinator.handle(new ReleaseHumanTakeover("ses-1", "user-1"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    verify(operationRepository)
        .transitionPhase("op-1", OperationPhase.EXECUTING, OperationPhase.COMPLETING);
    verify(nodeCommandGateway)
        .send(argThat(command -> command.commandType().equals("EndHumanTakeover")));
  }

  @Test
  void shouldCommitHumanTakeoverOnlyAfterNodeReleasedInputAndResyncedState() {
    var session = createSession("ses-1", SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(
            Optional.of(
                createActiveOperation(
                    "ses-1", OperationMode.HUMAN_TAKEOVER, OperationPhase.COMPLETING, "user-1")));
    var state =
        new NodeEvent.StateUpdated(
            "ses-1",
            4,
            4,
            "https://example.test",
            "Example",
            "hash-4",
            "COMPLETE",
            java.util.List.of());

    var result =
        coordinator.handle(
            nodeEvent(
                new NodeEvent.HumanTakeoverEnded("ses-1", "user-1", "USER_RELEASE", state), 0, 1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.COMMITTED);
  }

  @Test
  void shouldCommitHumanTakeoverAfterGatewayDisconnectBarrierAndResync() {
    var session = createSession("ses-1", SessionState.RUNNING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(
            Optional.of(
                createActiveOperation(
                    "ses-1", OperationMode.HUMAN_TAKEOVER, OperationPhase.EXECUTING, "user-1")));
    var state =
        new NodeEvent.StateUpdated(
            "ses-1",
            4,
            4,
            "https://example.test",
            "Example",
            "hash-4",
            "COMPLETE",
            java.util.List.of());

    var result =
        coordinator.handle(
            nodeEvent(
                new NodeEvent.HumanTakeoverEnded("ses-1", "user-1", "GATEWAY_DISCONNECT", state),
                0,
                1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(operationRepository)
        .transitionPhase("op-1", OperationPhase.EXECUTING, OperationPhase.COMPLETING);
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
            nodeEvent(
                new NodeEvent.RuntimeStopped(
                    "ses-1", "user_request", 0, "profile-test", "chk-test", 1, 1, 0, 0, "EMPTY"),
                0,
                1));

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
  void shouldCheckpointAndHibernateRunningSession() {
    var running = createSession("ses-1", SessionState.RUNNING);
    var hibernating = createSession("ses-1", SessionState.HIBERNATING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(running, hibernating);
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(1L);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(
            Optional.of(
                createActiveOperation(
                    "ses-1",
                    OperationMode.HIBERNATE,
                    OperationPhase.PREPARING,
                    "resource-decision-engine")));

    var accepted = coordinator.handle(new HibernateSession("ses-1", "resource_policy"));
    var completed =
        coordinator.handle(
            nodeEvent(
                new NodeEvent.RuntimeStopped(
                    "ses-1",
                    "resource_policy",
                    0,
                    "profile-test",
                    "chk-test",
                    1,
                    1,
                    1024,
                    4,
                    "COMMITTED"),
                0,
                1));

    assertThat(accepted.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    assertThat(completed.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(operationRepository).insert(argThat(op -> op.mode() == OperationMode.HIBERNATE));
    verify(nodeCommandGateway)
        .send(argThat(command -> command.commandType().equals("StopRuntime")));
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(context -> context.state() == SessionState.HIBERNATED), eq(0L));
    verify(outboxPublisher)
        .append(
            argThat(
                event ->
                    event instanceof SessionStateChanged changed
                        && changed.newState() == SessionState.HIBERNATED));
  }

  @Test
  void shouldRejectStaleContextEpochBeforeApplyingNodeEvent() {
    var session = createSession("ses-1", SessionState.STARTING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    var event =
        new NodeEvent.RuntimeStarted(
            "ses-1", "node-1", "runtime-1", 12345L, 1L, "http://localhost:9222", "", "", "", "");

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
            "ses-1", "node-1", "runtime-1", 12345L, 1L, "http://localhost:9222", "", "", "", "");

    var result = coordinator.handle(nodeEvent(event, 0, 2));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.REJECTED);
    assertThat(result.reason()).isEqualTo("STALE_OPERATION_EPOCH");
    verify(sessionRepository, never()).updateWithExpectedEpoch(any(), anyLong());
  }

  @Test
  void shouldHandleTimeout() {
    // Given
    when(sessionRepository.requireForUpdate("ses-1"))
        .thenReturn(createSession("ses-1", SessionState.RUNNING));

    // When
    var result = coordinator.handle(new OperationTimedOut("ses-1", "op-1"));

    // Then
    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.TIMED_OUT);
    verify(outboxPublisher).append(any(OperationTimedOutEvent.class));
  }

  @Test
  void shouldAbortInFlightStartAndCreateNewTermCleanupAfterFailover() {
    var session = createSession("ses-1", SessionState.STARTING, 2);
    var stale =
        createActiveOperation(
            "ses-1", OperationMode.AGENT_INTERACTIVE, OperationPhase.PREPARING, "control-plane", 1);
    when(ownershipService.acquireSession("ses-1", 1)).thenReturn(2L);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.of(stale));
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(2L);

    var result = coordinator.handle(new StartSession("ses-1", "runtime-1", "idem-failover"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.ABORTED);
    verify(operationRepository)
        .insert(
            argThat(
                operation ->
                    operation.mode() == OperationMode.TERMINATION
                        && operation.coordinatorTerm() == 2
                        && operation.operationEpoch() == 2));
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(
                context ->
                    context.state() == SessionState.TERMINATING && context.coordinatorTerm() == 2),
            eq(0L));
    verify(nodeCommandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("StopRuntime")
                        && command.coordinatorTerm() == 2
                        && command.operationEpoch() == 2));
    assertThat(meterRegistry.get(CoordinatorReconciliationMetrics.STALE_ABORTED).counter().count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.get(CoordinatorReconciliationMetrics.CLEANUP_STARTED).counter().count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.get(CoordinatorReconciliationMetrics.CLEANUP_FAILURES).counter().count())
        .isZero();
    assertThat(meterRegistry.get(CoordinatorReconciliationMetrics.DURATION).timer().count())
        .isEqualTo(1);
  }

  @Test
  void shouldRecordNewTermCleanupFailure() {
    var session = createSession("ses-1", SessionState.RECOVERING, 2);
    var stale =
        createActiveOperation(
            "ses-1", OperationMode.RECOVERY, OperationPhase.EXECUTING, "control-plane", 1);
    when(ownershipService.acquireSession("ses-1", 1)).thenReturn(2L);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.of(stale));
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(2L);
    doThrow(new IllegalStateException("node dispatcher unavailable"))
        .when(nodeCommandGateway)
        .send(argThat(command -> command.commandType().equals("StopRuntime")));

    assertThatThrownBy(() -> coordinator.handle(new TerminateSession("ses-1", "failover-cleanup")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("node dispatcher unavailable");

    assertThat(meterRegistry.get(CoordinatorReconciliationMetrics.STALE_ABORTED).counter().count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.get(CoordinatorReconciliationMetrics.CLEANUP_STARTED).counter().count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.get(CoordinatorReconciliationMetrics.CLEANUP_FAILURES).counter().count())
        .isEqualTo(1);
    assertThat(meterRegistry.get(CoordinatorReconciliationMetrics.DURATION).timer().count())
        .isEqualTo(1);
  }

  @Test
  void shouldRebuildHumanTakeoverBarrierUnderNewTerm() {
    var session = createSession("ses-1", SessionState.RUNNING, 2);
    var stale =
        createActiveOperation(
            "ses-1", OperationMode.HUMAN_TAKEOVER, OperationPhase.EXECUTING, "user-1", 1);
    when(ownershipService.acquireSession("ses-1", 1)).thenReturn(2L);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.of(stale));
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(5L);

    var result = coordinator.handle(new RequestHumanTakeover("ses-1", "user-1"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    assertThat(result.operationId()).isNotEqualTo("op-1");
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.ABORTED);
    verify(operationRepository)
        .insert(
            argThat(
                operation ->
                    operation.mode() == OperationMode.HUMAN_TAKEOVER
                        && operation.coordinatorTerm() == 2
                        && operation.operationEpoch() == 5));
    verify(nodeCommandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("BeginHumanTakeover")
                        && command.coordinatorTerm() == 2));
  }

  @Test
  void shouldFenceStaleAgentInputBeforeStartingHumanTakeover() {
    var session = createSession("ses-1", SessionState.RUNNING, 2);
    var stale =
        createActiveOperation(
            "ses-1", OperationMode.AGENT_INTERACTIVE, OperationPhase.EXECUTING, "task-1", 1);
    when(ownershipService.acquireSession("ses-1", 1)).thenReturn(2L);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.of(stale), Optional.empty());
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(6L);

    var result = coordinator.handle(new RequestHumanTakeover("ses-1", "user-2"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    var commands = inOrder(nodeCommandGateway);
    commands
        .verify(nodeCommandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("ReleaseAllInput")
                        && command.coordinatorTerm() == 2
                        && command.operationEpoch() == 0));
    commands
        .verify(nodeCommandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("BeginHumanTakeover")
                        && command.coordinatorTerm() == 2
                        && command.operationEpoch() == 6));
  }

  private SessionContext createSession(String sessionId, SessionState state) {
    return createSession(sessionId, state, 0);
  }

  private SessionContext createSession(String sessionId, SessionState state, long coordinatorTerm) {
    return new SessionContext(
        sessionId,
        "tenant-1",
        "profile-1",
        null,
        state == SessionState.CREATED ? null : "runtime-1",
        null,
        null,
        coordinatorTerm,
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
    return createActiveOperation(sessionId, mode, OperationPhase.PREPARING, "control-plane");
  }

  private ExclusiveOperation createActiveOperation(
      String sessionId, OperationMode mode, OperationPhase phase, String actorId) {
    return createActiveOperation(sessionId, mode, phase, actorId, 0);
  }

  private ExclusiveOperation createActiveOperation(
      String sessionId,
      OperationMode mode,
      OperationPhase phase,
      String actorId,
      long coordinatorTerm) {
    return new ExclusiveOperation(
        "op-1",
        sessionId,
        OwnerType.SYSTEM,
        actorId,
        mode,
        0,
        coordinatorTerm,
        0,
        1,
        null,
        true,
        true,
        phase,
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
