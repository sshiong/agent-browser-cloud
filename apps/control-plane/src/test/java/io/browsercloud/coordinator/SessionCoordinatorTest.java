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
import io.browsercloud.proto.node.v1.StartRuntimeCommand;
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
  @Mock private ProxyRuntimeBindingRepository proxyBindingRepository;
  @Mock private BrowserTransactionPolicyRepository browserTransactionPolicyRepository;
  @Mock private BrowserIdentitySpecRepository browserIdentitySpecRepository;
  @Mock private CoordinatorRouteAuthority routeAuthority;
  @Mock private CoordinatorShardLocality shardLocality;

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
            proxyBindingRepository,
            browserTransactionPolicyRepository,
            browserIdentitySpecRepository,
            routeAuthority,
            shardLocality);
    lenient().when(shardLocality.owns(anyInt())).thenReturn(true);
    lenient()
        .when(browserTransactionPolicyRepository.find(anyString(), anyString()))
        .thenReturn(BrowserTransactionPolicy.empty());
    lenient()
        .when(browserIdentitySpecRepository.require(anyString(), anyString()))
        .thenReturn(BrowserIdentitySpec.empty());
    lenient()
        .when(routeAuthority.resolve(anyString()))
        .thenAnswer(
            invocation ->
                new CoordinatorRouteAuthority.SessionRoute(
                    invocation.getArgument(0), "tenant-test", 1, 0, 0));
  }

  @Test
  void rejectsLifecycleCommandOnNonOwningPhysicalShard() {
    when(shardLocality.owns(0)).thenReturn(false);

    assertThatThrownBy(() -> coordinator.handle(new StartSession("ses-1", "runtime-1", "idem-1")))
        .isInstanceOf(SessionCoordinator.CoordinatorShardNotLocalException.class)
        .hasMessageContaining("COORDINATOR_SHARD_NOT_LOCAL");

    verify(sessionRepository, never()).lockForUpdate(anyString());
    verify(ownershipService, never()).acquireSession(anyString(), anyLong());
  }

  @Test
  void currentLogicalOwnerContinuesDuringPhysicalShardHandover() {
    when(shardLocality.owns(0)).thenReturn(false);
    when(ownershipService.isCurrentOwner("ses-1", 1)).thenReturn(true);
    when(ownershipService.acquireSession("ses-1", 1)).thenReturn(3L);
    when(sessionRepository.requireForUpdate("ses-1"))
        .thenReturn(createSession("ses-1", SessionState.RUNNING));
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.empty());

    var result = coordinator.handle(new ReconcileAgentExecution("ses-1", "task-1"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(ownershipService).acquireSession("ses-1", 1);
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
  void shouldDeliverTheCommittedNonSecretProxyDescriptorToTheNode() throws Exception {
    var session =
        createSession("ses-1", SessionState.CREATED).withProxyBinding("pxy_provider_binding");
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(1L);
    when(proxyBindingRepository.find("ses-1", "pxy_provider_binding"))
        .thenReturn(
            Optional.of(
                new ProxyRuntimeBinding(
                    "pxy_provider_binding",
                    "provider-a",
                    "203.0.113.10",
                    "vault://tenant-test/proxy/a")));

    var result = coordinator.handle(new StartSession("ses-1", "runtime-1", "idem-proxy"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    var command = org.mockito.ArgumentCaptor.forClass(NodeCommand.class);
    verify(nodeCommandGateway).send(command.capture());
    var payload = StartRuntimeCommand.parseFrom(command.getValue().payload());
    assertThat(payload.getProxyBindingId()).isEqualTo("pxy_provider_binding");
    assertThat(payload.getProxyProviderId()).isEqualTo("provider-a");
    assertThat(payload.getProxyExpectedExitIp()).isEqualTo("203.0.113.10");
    assertThat(payload.getProxyCredentialRef()).isEqualTo("vault://tenant-test/proxy/a");
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
  void sendsExactVersionBrowserTransactionPolicyOnRuntimeStart() throws Exception {
    var session = createSession("ses-1", SessionState.CREATED);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(1L);
    when(browserTransactionPolicyRepository.find("ses-1", "tenant-1"))
        .thenReturn(
            new BrowserTransactionPolicy(
                7,
                java.util.List.of("https://crm.example.test"),
                java.util.List.of("/api/authorize"),
                java.util.List.of("/cases/finalize"),
                "a".repeat(64)));

    coordinator.handle(new StartSession("ses-1", "runtime-1", "idem-policy"));

    var command = org.mockito.ArgumentCaptor.forClass(NodeCommand.class);
    verify(nodeCommandGateway).send(command.capture());
    var payload = StartRuntimeCommand.parseFrom(command.getValue().payload());
    assertThat(payload.getBrowserTransactionPolicyVersion()).isEqualTo(7);
    assertThat(payload.getBrowserTransactionExpectedOriginsList())
        .containsExactly("https://crm.example.test");
    assertThat(payload.getPaymentSecurityRoutePrefixesList()).containsExactly("/api/authorize");
    assertThat(payload.getCriticalTransactionRoutePrefixesList())
        .containsExactly("/cases/finalize");
    assertThat(payload.getBrowserTransactionPolicyHash()).isEqualTo("a".repeat(64));
  }

  @Test
  void sendsLockedBrowserIdentityOnEveryRuntimeStart() throws Exception {
    var session = createSession("ses-1", SessionState.CREATED);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(1L);
    when(browserIdentitySpecRepository.require("ses-1", "tenant-1"))
        .thenReturn(
            new BrowserIdentitySpec(
                "BrowserCloud-Test-UA",
                "Asia/Shanghai",
                "zh-CN",
                java.util.List.of("zh-CN", "en-US"),
                "PROXY_ONLY",
                "SYSTEM",
                1280,
                720,
                1920,
                1080,
                new java.math.BigDecimal("1.25"),
                "chromium-standard-v1",
                "linux-desktop-v1",
                4,
                "b".repeat(64)));

    coordinator.handle(new StartSession("ses-1", "runtime-1", "idem-identity"));

    var command = org.mockito.ArgumentCaptor.forClass(NodeCommand.class);
    verify(nodeCommandGateway).send(command.capture());
    var payload = StartRuntimeCommand.parseFrom(command.getValue().payload());
    assertThat(payload.getIdentityUserAgent()).isEqualTo("BrowserCloud-Test-UA");
    assertThat(payload.getIdentityTimezone()).isEqualTo("Asia/Shanghai");
    assertThat(payload.getIdentityLanguagesList()).containsExactly("zh-CN", "en-US");
    assertThat(payload.getIdentityViewportWidth()).isEqualTo(1280);
    assertThat(payload.getIdentitySpecVersion()).isEqualTo(4);
    assertThat(payload.getIdentitySpecHash()).isEqualTo("b".repeat(64));
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
  void shouldKeepActiveAgentOperationWhenHumanJoinsCollaborativeDesktop() {
    var session = createSession("ses-1", SessionState.RUNNING);
    var agent =
        createActiveOperation(
            "ses-1", OperationMode.AGENT_INTERACTIVE, OperationPhase.EXECUTING, "task-1");
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.of(agent));

    var result = coordinator.handle(new RequestHumanTakeover("ses-1", "user-1"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    assertThat(result.operationId()).isEqualTo(agent.operationId());
    verify(operationRepository, never())
        .transition(agent.operationId(), OperationState.ACTIVE, OperationState.ABORTED);
    verify(operationRepository, never()).insert(any());
    verify(nodeCommandGateway, never())
        .send(argThat(command -> command.commandType().equals("BeginHumanTakeover")));
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
  void resourceAdjustmentAckOnlyPassesTheCoordinatorFenceBeforeApplicationCommit() {
    var session =
        createSession("ses-1", SessionState.RUNNING).withPlacement("node-1", ResourceClass.L2, 1);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(
            Optional.of(
                OperationFactory.resourceAdjustment(session, 1, "op-1")
                    .withPhase(OperationPhase.EXECUTING)));
    var adjusted =
        new NodeEvent.RuntimeResourcesAdjusted(
            "ses-1",
            "node-1",
            "L2",
            600,
            768,
            1280,
            256,
            8,
            "L2",
            900,
            1024,
            1792,
            256,
            8,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "SUSTAINED_CPU_PRESSURE",
            "op-1");

    var result = coordinator.handle(nodeEvent(adjusted, 1, 1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(operationRepository, never()).transitionPhase(anyString(), any(), any());
    verify(operationRepository, never()).transition(anyString(), any(), any());
  }

  @Test
  void warmTierBarrierCanCommitWhileTheSameBrowserContextIsStopping() {
    var session =
        createSession("ses-1", SessionState.TERMINATING)
            .withPlacement("node-1", ResourceClass.L2, 1);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    var synced = warmTierEvent("node-1");

    var result = coordinator.handle(nodeEvent(synced, 1, 0));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
  }

  @Test
  void oldContextWarmTierBarrierIsTerminallyRejectedAfterMigration() {
    var session =
        createSession("ses-1", SessionState.RUNNING).withPlacement("node-2", ResourceClass.L2, 1);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);

    var result = coordinator.handle(nodeEvent(warmTierEvent("node-1"), 0, 0));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.REJECTED);
    assertThat(result.reason()).isEqualTo("STALE_PROFILE_WARM_TIER_CONTEXT");
  }

  @Test
  void warmTierBarrierCannotBeAttributedToAnotherProfile() {
    var session =
        createSession("ses-1", SessionState.RUNNING).withPlacement("node-1", ResourceClass.L2, 1);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(session);
    var foreign =
        new NodeEvent.ProfileWarmTierSynced(
            "ses-1",
            "node-1",
            "profile-other",
            1,
            1,
            "wtb_1_1_12345678",
            1,
            0,
            0,
            32,
            0,
            "a".repeat(64),
            1_785_283_200_000L);

    var result = coordinator.handle(nodeEvent(foreign, 1, 0));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.REJECTED);
    assertThat(result.reason()).isEqualTo("INVALID_PROFILE_WARM_TIER_EVENT");
  }

  private static NodeEvent.ProfileWarmTierSynced warmTierEvent(String nodeId) {
    return new NodeEvent.ProfileWarmTierSynced(
        "ses-1",
        nodeId,
        "profile-1",
        1,
        1,
        "wtb_1_1_12345678",
        1,
        0,
        0,
        32,
        0,
        "a".repeat(64),
        1_785_283_200_000L);
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
  void shouldFenceAndStopFailedMigrationTargetBeforeRetry() {
    var failed = createSession("ses-1", SessionState.FAILED);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(failed);
    when(operationRepository.findActive("ses-1")).thenReturn(Optional.empty());
    when(operationRepository.nextOperationEpoch("ses-1")).thenReturn(2L);

    var result =
        coordinator.handle(new CleanupMigrationTarget("ses-1", "migration_target_restore_failed"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.ACCEPTED);
    verify(operationRepository)
        .insert(argThat(operation -> operation.mode() == OperationMode.MIGRATION_CLEANUP));
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(context -> context.state() == SessionState.HIBERNATING), eq(0L));
    verify(nodeCommandGateway)
        .send(
            argThat(
                command ->
                    command.commandType().equals("StopRuntime") && command.operationEpoch() == 2));
  }

  @Test
  void shouldCommitMigrationTargetCleanupOnlyAfterRuntimeStopped() {
    var hibernating = createSession("ses-1", SessionState.HIBERNATING);
    when(sessionRepository.requireForUpdate("ses-1")).thenReturn(hibernating);
    when(operationRepository.findActive("ses-1"))
        .thenReturn(Optional.of(createActiveOperation("ses-1", OperationMode.MIGRATION_CLEANUP)));

    var result =
        coordinator.handle(
            nodeEvent(
                new NodeEvent.RuntimeStopped(
                    "ses-1",
                    "migration_target_restore_failed",
                    0,
                    "profile-test",
                    "",
                    1,
                    1,
                    0,
                    0,
                    "EMPTY"),
                0,
                1));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(context -> context.state() == SessionState.HIBERNATED), eq(0L));
    verify(operationRepository).transition("op-1", OperationState.ACTIVE, OperationState.COMMITTED);
  }

  @Test
  void hibernateTimeoutFailsClosedInsteadOfLeavingSessionStuck() {
    when(sessionRepository.requireForUpdate("ses-1"))
        .thenReturn(createSession("ses-1", SessionState.HIBERNATING));

    var result = coordinator.handle(new OperationTimedOut("ses-1", "op-1"));

    assertThat(result.status()).isEqualTo(CoordinatorResult.Status.COMPLETED);
    verify(sessionRepository)
        .updateWithExpectedEpoch(
            argThat(context -> context.state() == SessionState.FAILED), eq(0L));
    verify(outboxPublisher)
        .append(
            argThat(
                event ->
                    event instanceof SessionStateChanged changed
                        && changed.newState() == SessionState.FAILED));
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
