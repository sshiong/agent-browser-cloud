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
  @Mock private RemoteDesktopParticipantApplicationService remoteDesktopParticipants;
  @Mock private ProfileWarmTierApplicationService profileWarmTier;
  @Mock private SessionRecordingApplicationService recordingService;
  @Mock private ChallengeAutomationApplicationService challengeAutomationService;
  @Mock private ChallengeInputApplicationService challengeInputService;

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
            humanAssistService,
            remoteDesktopParticipants,
            profileWarmTier,
            recordingService,
            challengeAutomationService,
            challengeInputService);
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
    verify(challengeAutomationService).evidenceCaptured("tenant-test", evidence);
  }

  @Test
  void shouldRouteFencedChallengeAutomationFailureInsideTheInboxTransaction() {
    var failure =
        new NodeEvent.ChallengeAutomationFailed(
            "ses-test",
            "car_1234567890abcdefghij",
            "cvj_1234567890abcdefghij",
            "chl_1234567890abcdefghij",
            3,
            "STALE_CHALLENGE_SCREENSHOT");
    var command =
        new NodeEventReceived(
            "evt-challenge-automation-failed", "tenant-test", "ses-test", 1, 2, 9, 5, failure);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(challengeAutomationService).failed(command, failure);
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldPersistRecordingManifestInsideTheInboxTransaction() {
    var recording =
        new NodeEvent.RecordingFinalized(
            "ses_test",
            "rec_1234567890abcdef1234567890abcdef",
            "node-test",
            4,
            120,
            3,
            6,
            8,
            1,
            "tenants/tenant-test/profiles/p/sessions/ses_test/recordings/rec/COMMITTED",
            "b".repeat(64),
            512,
            1_785_283_100_000L,
            1_785_283_200_000L);
    var command =
        new NodeEventReceived("evt_recording", "tenant-test", "ses_test", 1, 1, 0, 2, recording);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(recordingService).record("tenant-test", "evt_recording", recording);
    verify(inboxRepository).save(any());
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
  void shouldPersistEvaluationStateWithoutTreatingItAsAnAgentTaskStep() {
    var state =
        new NodeEvent.StateUpdated(
            "ses-test",
            10,
            4,
            "https://example.test",
            "Example",
            "a".repeat(64),
            "COMPLETE",
            java.util.List.of(),
            "AGENT_EVALUATE",
            "aje_1234567890abcdefghij");
    var command =
        new NodeEventReceived("evt-evaluate-state", "tenant-test", "ses-test", 1, 2, 7, 3, state);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(browserStateRepository).save("tenant-test", 2, state);
    verify(agentNavigationCompletionService, never()).stateUpdated(any(), any(), any());
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldCommitAgentSuppliedOtpBeforeRunningChallengeDetectionAgain() {
    var state =
        new NodeEvent.StateUpdated(
            "ses-test",
            15,
            7,
            "https://example.test/verify",
            "Verify",
            "hash-15",
            "COMPLETE",
            java.util.List.of(),
            "AGENT_TYPE_TEXT",
            "step_human_1234567890abcdef");
    var command =
        new NodeEventReceived("evt-agent-otp", "tenant-test", "ses-test", 1, 2, 9, 6, state);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(challengeInputService.stateUpdated(command, state)).thenReturn(true);

    service.receive(command);

    verify(browserStateRepository).save("tenant-test", 2, state);
    verify(challengeInputService).stateUpdated(command, state);
    verify(challengeDetectionService, never()).observe(any(), any());
    verify(agentNavigationCompletionService, never()).stateUpdated(any(), any(), any());
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldRouteBoundedOtpInputFailureWithoutFailingTheOriginalAgentStep() {
    var failed =
        new NodeEvent.AgentActionFailed(
            "ses-test",
            "agt_1234567890abcdef",
            "step_human_1234567890abcdef",
            "TYPE_TEXT",
            "TARGET_REVISION_STALE");
    var command =
        new NodeEventReceived(
            "evt-agent-otp-failed", "tenant-test", "ses-test", 1, 2, 9, 6, failed);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(challengeInputService.failed(command, failed)).thenReturn(true);

    service.receive(command);

    verify(challengeInputService).failed(command, failed);
    verify(agentNavigationCompletionService, never()).actionFailed(any(), any());
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
  void shouldCommitProfileWarmTierBarrierBeforeAcknowledgingInbox() {
    var synced =
        new NodeEvent.ProfileWarmTierSynced(
            "ses-test",
            "node-test",
            "profile-test",
            4,
            12,
            "barrier-12",
            3,
            1,
            8,
            4096,
            2,
            "a".repeat(64),
            1_785_283_200_000L);
    var command =
        new NodeEventReceived("evt-warm-tier", "tenant-test", "ses-test", 1, 2, 0, 5, synced);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(profileWarmTier).record(command, synced);
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldTerminallyRecordInvalidResourceAcknowledgementBeforeAcknowledgingInbox() {
    var adjusted = resourceAdjustedEvent();
    var command =
        new NodeEventReceived(
            "evt-resource-invalid", "tenant-test", "ses-test", 1, 2, 3, 5, adjusted);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    org.mockito.Mockito.doThrow(
            new SessionResourceApplicationService.ResourceTelemetryRejectedException(
                "RESOURCE_ADJUSTMENT_ACK_MISMATCH"))
        .when(resourceService)
        .recordAdjustmentAcknowledged("tenant-test", adjusted);

    service.receive(command);

    verify(resourceService)
        .recordAdjustmentRejected("tenant-test", adjusted, "RESOURCE_ADJUSTMENT_ACK_MISMATCH");
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldConsumeLateAcknowledgementOnlyWhenItsDurableAdjustmentAlreadyFailed() {
    var adjusted = resourceAdjustedEvent();
    var command =
        new NodeEventReceived("evt-resource-late", "tenant-test", "ses-test", 1, 2, 3, 5, adjusted);
    when(coordinator.handle(command))
        .thenReturn(CoordinatorResult.rejected("STALE_RESOURCE_OPERATION"));
    when(resourceService.recordLateAdjustmentAcknowledgement(
            "tenant-test", adjusted, "STALE_RESOURCE_OPERATION"))
        .thenReturn(true);

    var receipt = service.receive(command);

    assertThat(receipt.duplicate()).isFalse();
    verify(resourceService, never()).recordAdjustmentAcknowledged(any(), any());
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldStillRejectAStaleResourceAcknowledgementWithoutFailedLedgerEvidence() {
    var adjusted = resourceAdjustedEvent();
    var command =
        new NodeEventReceived(
            "evt-resource-stale", "tenant-test", "ses-test", 1, 2, 3, 5, adjusted);
    when(coordinator.handle(command))
        .thenReturn(CoordinatorResult.rejected("STALE_RESOURCE_OPERATION"));

    assertThatThrownBy(() -> service.receive(command))
        .isInstanceOf(NodeEventIngestionService.NodeEventRejectedException.class)
        .hasMessageContaining("STALE_RESOURCE_OPERATION");
    verify(inboxRepository, never()).save(any());
  }

  @Test
  void shouldNeverConsumeAResourceNodeMismatchAsALateAcknowledgement() {
    var adjusted = resourceAdjustedEvent();
    var command =
        new NodeEventReceived(
            "evt-resource-wrong-node", "tenant-test", "ses-test", 1, 2, 3, 5, adjusted);
    when(coordinator.handle(command))
        .thenReturn(CoordinatorResult.rejected("RESOURCE_NODE_MISMATCH"));

    assertThatThrownBy(() -> service.receive(command))
        .isInstanceOf(NodeEventIngestionService.NodeEventRejectedException.class)
        .hasMessageContaining("RESOURCE_NODE_MISMATCH");
    verify(inboxRepository, never()).save(any());
  }

  private static NodeEvent.RuntimeResourcesAdjusted resourceAdjustedEvent() {
    return new NodeEvent.RuntimeResourcesAdjusted(
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
