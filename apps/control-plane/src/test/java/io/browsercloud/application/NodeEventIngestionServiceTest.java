package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.CoordinatorResult;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.InboxEventJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeEventIngestionServiceTest {

  @Mock private InboxEventJpaRepository inboxRepository;
  @Mock private SessionCoordinator coordinator;
  @Mock private BrowserStateRepository browserStateRepository;
  @Mock private ProfileApplicationService profileApplicationService;
  @Mock private StaticProxyApplicationService proxyApplicationService;
  @Mock private SessionRepository sessionRepository;
  @Mock private NodeCommandGateway nodeCommandGateway;
  @Mock private AgentNavigationCompletionService agentNavigationCompletionService;
  @Mock private AuditApplicationService auditService;
  @Mock private DurableWorkflowApplicationService workflowService;
  @Mock private BrowserCapacityApplicationService browserCapacityService;
  @Mock private SessionResourceApplicationService resourceService;
  @Mock private BusinessRecoveryActionApplicationService recoveryActionService;
  @Mock private SessionEvidenceApplicationService evidenceService;
  @Mock private StateResyncAdmissionService stateResyncAdmissionService;
  @Mock private BrowserStateSnapshotAssembler stateSnapshotAssembler;
  @Mock private ChallengeDetectionService challengeDetectionService;
  @Mock private HumanAssistApplicationService humanAssistService;

  private NodeEventIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new NodeEventIngestionService(
            inboxRepository,
            coordinator,
            browserStateRepository,
            profileApplicationService,
            proxyApplicationService,
            sessionRepository,
            nodeCommandGateway,
            agentNavigationCompletionService,
            auditService,
            workflowService,
            browserCapacityService,
            resourceService,
            recoveryActionService,
            evidenceService,
            stateResyncAdmissionService,
            stateSnapshotAssembler,
            challengeDetectionService,
            humanAssistService);
    org.mockito.Mockito.lenient()
        .when(humanAssistService.stateUpdated(any(), any()))
        .thenReturn(HumanAssistApplicationService.StateCommit.notHumanAssist());
  }

  @Test
  void shouldAcknowledgeDuplicateWithoutReapplyingCoordinatorState() {
    var command = command();
    when(inboxRepository.existsById(command.eventId())).thenReturn(true);

    var receipt = service.receive(command);

    assertThat(receipt.duplicate()).isTrue();
    verify(coordinator, never()).handle(any());
  }

  @Test
  void shouldRecordInboxOnlyAfterCoordinatorCompletes() {
    var command = command();
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    var receipt = service.receive(command);

    assertThat(receipt.duplicate()).isFalse();
    verify(profileApplicationService)
        .recordCheckpoint("tenant-test", (NodeEvent.RuntimeStopped) command.event());
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldPersistEvidenceMetadataInsideTheInboxTransaction() {
    var evidence =
        new NodeEvent.EvidenceCaptured(
            "ses_test",
            "evd_1234567890abcdef",
            "AGENT_ACTION_FAILURE",
            "agt_1234567890abcdef",
            "step_1234567890abcd",
            "cmd_1234567890abcdef",
            "a".repeat(64),
            1024,
            "tenants/tenant-test/evidence/evd_1234567890abcdef/screenshot.jpeg",
            1_785_283_200_000L,
            true,
            "COMMITTED",
            "",
            "MASKED",
            1);
    var command =
        new NodeEventReceived("evt_evidence", "tenant-test", "ses_test", 1, 1, 0, 2, evidence);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(evidenceService).record("tenant-test", "evt_evidence", evidence);
  }

  @Test
  void shouldNotRecordRejectedEvent() {
    var command = command();
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.rejected("STALE_CONTEXT_EPOCH"));

    assertThatThrownBy(() -> service.receive(command))
        .isInstanceOf(NodeEventIngestionService.NodeEventRejectedException.class)
        .hasMessageContaining("STALE_CONTEXT_EPOCH");
    verify(inboxRepository, never()).save(any());
  }

  @Test
  void shouldPersistAcceptedBrowserStateBeforeAcknowledgingInbox() {
    var state =
        new NodeEvent.StateUpdated(
            "ses-test",
            4,
            4,
            "https://example.test",
            "Example",
            "hash-4",
            "COMPLETE",
            java.util.List.of());
    var command = new NodeEventReceived("evt-state", "tenant-test", "ses-test", 0, 2, 0, 2, state);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(browserStateRepository).save("tenant-test", 2, state);
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldKeepAgentPausedWhenHumanAssistRevealsAnotherChallenge() {
    var state =
        new NodeEvent.StateUpdated(
            "ses-test",
            13,
            5,
            "https://example.test",
            "Verify",
            "hash-13",
            "COMPLETE",
            java.util.List.of(),
            "HUMAN_ASSIST",
            "hint_1234567890abcdefghij");
    var command = new NodeEventReceived("evt-assist", "tenant-test", "ses-test", 1, 2, 3, 4, state);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(humanAssistService.stateUpdated(command, state))
        .thenReturn(
            HumanAssistApplicationService.StateCommit.committed("chl_1234567890abcdefghij"));
    when(challengeDetectionService.observe(command, state))
        .thenReturn(java.util.Optional.of("chl_abcdefghijklmnopqrst"));

    service.receive(command);

    verify(agentNavigationCompletionService)
        .challengeObserved("ses-test", "tenant-test", "chl_abcdefghijklmnopqrst");
    verify(agentNavigationCompletionService, never()).stateUpdated(any(), any(), any());
    verify(humanAssistService)
        .continueAgentAfterState(
            "chl_1234567890abcdefghij", "chl_abcdefghijklmnopqrst", "tenant-test");
  }

  @Test
  void shouldContinueChallengeFlowFromAuthoritativeHumanTakeoverEndState() {
    var state =
        new NodeEvent.StateUpdated(
            "ses-test",
            14,
            6,
            "https://example.test",
            "Verified",
            "hash-14",
            "COMPLETE",
            java.util.List.of());
    var ended = new NodeEvent.HumanTakeoverEnded("ses-test", "user-test", "USER_RELEASE", state);
    var command =
        new NodeEventReceived("evt-takeover-end", "tenant-test", "ses-test", 1, 2, 4, 5, ended);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(browserStateRepository).save("tenant-test", 2, state);
    verify(humanAssistService).humanTakeoverEnded(command, ended, null);
    verify(agentNavigationCompletionService, never()).stateUpdated(any(), any(), any());
  }

  @Test
  void shouldApplyResourceAdjustmentBeforeAcknowledgingInbox() {
    var adjusted =
        new NodeEvent.RuntimeResourcesAdjusted(
            "ses-test",
            "node-test",
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
            100,
            8000,
            75,
            6000,
            100,
            150,
            0,
            0,
            false,
            true,
            false,
            true,
            java.util.List.of(),
            java.util.List.of("automation.extension"),
            100,
            10,
            30,
            5,
            "SUSTAINED_MEMORY_PRESSURE",
            "op-resource");
    var command =
        new NodeEventReceived("evt-resource", "tenant-test", "ses-test", 1, 2, 3, 5, adjusted);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(resourceService).recordAdjustmentAcknowledged("tenant-test", adjusted);
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldProtectAgentWhenRuntimeCrashWasCausedByCgroupOom() {
    var crashed = new NodeEvent.RuntimeCrashed("ses-test", "OOM", "OOM: Chromium exited");
    var command = new NodeEventReceived("evt-oom", "tenant-test", "ses-test", 1, 2, 0, 5, crashed);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(resourceService)
        .protectRuntimeCrash("ses-test", "tenant-test", "OOM", "NODE_RUNTIME_EVENT");
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldApplyDiffAgainstTheDeclaredBaseVersion() {
    var diff =
        new NodeEvent.StateDiff(
            "ses-test",
            4,
            5,
            2,
            "https://example.test",
            "Changed",
            "hash-5",
            "COMPLETE",
            java.util.List.of(),
            java.util.List.of("target:2:old"));
    var command = new NodeEventReceived("evt-diff", "tenant-test", "ses-test", 0, 2, 0, 3, diff);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(browserStateRepository.applyDiff("tenant-test", 2, diff)).thenReturn(true);

    service.receive(command);

    verify(browserStateRepository).applyDiff("tenant-test", 2, diff);
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldInvalidateStateWhenDiffBaseCannotBeApplied() {
    var diff =
        new NodeEvent.StateDiff(
            "ses-test",
            4,
            6,
            2,
            "https://example.test",
            "Gap",
            "hash-6",
            "COMPLETE",
            java.util.List.of(),
            java.util.List.of());
    var command = new NodeEventReceived("evt-gap", "tenant-test", "ses-test", 0, 2, 0, 4, diff);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(browserStateRepository.applyDiff("tenant-test", 2, diff)).thenReturn(false);
    when(sessionRepository.requireForUpdate("ses-test")).thenReturn(runningSession());
    when(stateResyncAdmissionService.tryAdmitAutomaticFull(
            org.mockito.ArgumentMatchers.eq("tenant-test"),
            org.mockito.ArgumentMatchers.eq("ses-test"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("AUTO_BASE_VERSION_MISMATCH")))
        .thenReturn(new StateResyncAdmissionService.AdmissionDecision(true, "", 0));

    service.receive(command);

    verify(browserStateRepository)
        .invalidate("tenant-test", 2, "ses-test", 6, "BASE_VERSION_MISMATCH");
    verify(nodeCommandGateway).send(any());
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldCommitInvalidStateButNotQueueAnotherResyncWhenAutomaticCircuitIsOpen() {
    var truncated =
        new NodeEvent.DiffTruncated("ses-test", "BACKPRESSURE_LIMIT", 4, 8, "document", 40);
    var command =
        new NodeEventReceived("evt-backpressure", "tenant-test", "ses-test", 0, 2, 0, 5, truncated);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(sessionRepository.requireForUpdate("ses-test")).thenReturn(runningSession());
    when(stateResyncAdmissionService.tryAdmitAutomaticFull(
            org.mockito.ArgumentMatchers.eq("tenant-test"),
            org.mockito.ArgumentMatchers.eq("ses-test"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("AUTO_BACKPRESSURE_LIMIT")))
        .thenReturn(
            new StateResyncAdmissionService.AdmissionDecision(false, "AUTOMATIC_CIRCUIT", 60));

    service.receive(command);

    verify(browserStateRepository)
        .invalidate("tenant-test", 2, "ses-test", 8, "BACKPRESSURE_LIMIT");
    verify(nodeCommandGateway, never()).send(any());
    verify(auditService)
        .append(
            org.mockito.ArgumentMatchers.argThat(
                record ->
                    record.eventType().equals("STATE_RESYNC_CIRCUIT_OPEN")
                        && record.result().equals("BLOCKED")
                        && record.requestId().equals("evt-backpressure")));
    verify(inboxRepository).save(any());
  }

  private static io.browsercloud.domain.session.SessionContext runningSession() {
    return new io.browsercloud.domain.session.SessionContext(
        "ses-test",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-test",
        null,
        null,
        0,
        2,
        1,
        0,
        io.browsercloud.domain.session.ResourceClass.L2,
        io.browsercloud.domain.session.SessionState.RUNNING,
        "",
        java.time.Instant.EPOCH,
        java.time.Instant.EPOCH);
  }

  private NodeEventReceived command() {
    return new NodeEventReceived(
        "evt-test",
        "tenant-test",
        "ses-test",
        0,
        0,
        1,
        1,
        new NodeEvent.RuntimeStopped(
            "ses-test", "test", 0, "profile-test", "chk-test", 1, 1, 0, 0, "EMPTY"));
  }
}
