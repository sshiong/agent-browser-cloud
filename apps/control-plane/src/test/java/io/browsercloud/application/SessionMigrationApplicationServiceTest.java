package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.BusinessRecoveryModels.BusinessRecoveryValidationView;
import io.browsercloud.api.BusinessRecoveryModels.Verdict;
import io.browsercloud.api.OperationResponse;
import io.browsercloud.api.SafePointModels.SessionSafePointView;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionDescriptor;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionMigrationJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionMigrationApplicationServiceTest {

  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String TENANT_ID = "tenant-a";

  private final SessionMigrationJpaRepository migrations =
      mock(SessionMigrationJpaRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final BrowserCapacityApplicationService capacity =
      mock(BrowserCapacityApplicationService.class);
  private final SessionApplicationService sessionService = mock(SessionApplicationService.class);
  private final ProfileApplicationService profiles = mock(ProfileApplicationService.class);
  private final SafePointApplicationService safePoints = mock(SafePointApplicationService.class);
  private final StateGatewayApplicationService stateGateway =
      mock(StateGatewayApplicationService.class);
  private final BrowserStateRepository browserStates = mock(BrowserStateRepository.class);
  private final ApplicationBusinessRecoveryService recoveryService =
      mock(ApplicationBusinessRecoveryService.class);
  private final BusinessRecoveryActionApplicationService recoveryActions =
      mock(BusinessRecoveryActionApplicationService.class);
  private final SessionResourceApplicationService resources =
      mock(SessionResourceApplicationService.class);
  private final StaticProxyApplicationService proxies = mock(StaticProxyApplicationService.class);
  private final SessionMigrationApplicationService service =
      new SessionMigrationApplicationService(
          migrations,
          sessions,
          capacity,
          sessionService,
          profiles,
          safePoints,
          stateGateway,
          browserStates,
          recoveryService,
          recoveryActions,
          resources,
          proxies,
          new ObjectMapper());

  @Test
  void automaticHibernateLocksSessionBeforeFinalSafePointAssessmentAndDispatch() {
    var session = session();
    var now = Instant.now();
    var expected = new OperationResponse("op_1234567890abcdef", OperationState.ACTIVE);
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session);
    when(safePoints.assess(SESSION_ID, TENANT_ID))
        .thenReturn(
            new SessionSafePointView(
                SESSION_ID, true, "SAFE", "LIVE", "node-a", 7, now, now, List.of()));
    when(sessionService.hibernateForResourcePolicy(SESSION_ID, TENANT_ID)).thenReturn(expected);

    assertThat(service.hibernateAtSafePoint(SESSION_ID, TENANT_ID)).isEqualTo(expected);

    var order = inOrder(sessions, safePoints, sessionService);
    order.verify(sessions).requireForUpdate(SESSION_ID);
    order.verify(safePoints).assess(SESSION_ID, TENANT_ID);
    order.verify(sessionService).hibernateForResourcePolicy(SESSION_ID, TENANT_ID);
  }

  @Test
  void proxyRebindSnapshotsTargetAndDispatchesCheckpointOnlyAtSafePoint() {
    var now = Instant.now();
    var descriptor = mock(SessionDescriptor.class);
    when(descriptor.region()).thenReturn("singapore");
    when(migrations.findByTenantIdAndWorkflowTypeAndIdempotencyKey(
            TENANT_ID, "PROXY_REBIND", "idem-rebind"))
        .thenReturn(Optional.empty());
    when(migrations.findFirstBySessionIdAndPhaseInOrderByCreatedAtDesc(any(), any()))
        .thenReturn(Optional.empty());
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session());
    when(sessions.describe(SESSION_ID)).thenReturn(descriptor);
    when(safePoints.assess(SESSION_ID, TENANT_ID))
        .thenReturn(
            new SessionSafePointView(
                SESSION_ID, true, "SAFE", "LIVE", "node-a", 7, now, now, List.of()));
    when(proxies.validateRebindTarget(SESSION_ID, TENANT_ID, "pbind_target0000000001", "singapore"))
        .thenReturn(
            new StaticProxyApplicationService.RebindTargetSnapshot(
                "pbind_source0000000001", "pbind_target0000000001", 4));
    when(migrations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(sessionService.hibernateForProxyRebind(SESSION_ID, TENANT_ID, "admin-a"))
        .thenReturn(new OperationResponse("op_hibernate00000001", OperationState.ACTIVE));

    var response =
        service.requestProxyRebind(
            SESSION_ID,
            TENANT_ID,
            "admin-a",
            "pbind_target0000000001",
            "Move to the approved Singapore exit",
            "idem-rebind",
            "req-rebind");

    assertThat(response.workflowId()).startsWith("prb_");
    assertThat(response.operationId()).isEqualTo("op_hibernate00000001");
    verify(migrations, atLeastOnce())
        .save(
            argThat(
                workflow ->
                    workflow.isProxyRebind()
                        && workflow.getSourceProxyAllocationId().equals("proxy-a")
                        && workflow
                            .getTargetProxyBindingProfileId()
                            .equals("pbind_target0000000001")
                        && workflow.getTargetProxyBindingVersion() == 4
                        && workflow.getPhase().equals("CHECKPOINTING")));
    var order = inOrder(sessions, safePoints, proxies, sessionService);
    order.verify(sessions).requireForUpdate(SESSION_ID);
    order.verify(safePoints).assess(SESSION_ID, TENANT_ID);
    order
        .verify(proxies)
        .validateRebindTarget(SESSION_ID, TENANT_ID, "pbind_target0000000001", "singapore");
    order.verify(sessionService).hibernateForProxyRebind(SESSION_ID, TENANT_ID, "admin-a");
  }

  @Test
  void migrationCompletesOnlyAfterApplicationAwareReadyVerdict() {
    var now = Instant.now();
    var migration =
        new io.browsercloud.persistence.SessionMigrationEntity(
            "mig_1234567890abcdef", SESSION_ID, TENANT_ID, "node-a", 6, now);
    migration.targetPlaced("node-b", 7, "checkpoint-a", now);
    migration.stateResync("resync-a", now);
    migration.businessValidation(now);
    var state =
        new NodeEvent.StateUpdated(
            SESSION_ID,
            10,
            10,
            "https://crm.example.test/customers",
            "Customers",
            "hash",
            "COMPLETE",
            List.of());
    when(migrations.findById(migration.getMigrationId())).thenReturn(Optional.of(migration));
    when(browserStates.find(SESSION_ID))
        .thenReturn(Optional.of(new BrowserStateRepository.Snapshot(TENANT_ID, 7, state)));
    when(recoveryService.validateForMigration(SESSION_ID, TENANT_ID, migration.getMigrationId(), 0))
        .thenReturn(
            new BusinessRecoveryValidationView(
                "brv_1234567890abcdefghij",
                SESSION_ID,
                "crm",
                1L,
                7,
                10,
                Verdict.READY,
                true,
                List.of("APPLICATION_CONTRACT_SATISFIED"),
                "MIGRATION",
                migration.getMigrationId(),
                now));
    when(recoveryActions.request(any(), any())).thenReturn(false);

    service.reconcile(migration.getMigrationId());

    assertThat(migration.getPhase()).isEqualTo("COMPLETED");
    assertThat(migration.getRecoveryResult()).isEqualTo("READY");
    verify(migrations).save(migration);
    verify(resources)
        .recordMigrationPhase(
            SESSION_ID, migration.getMigrationId(), "COMPLETED", "READY", true, true);
  }

  @Test
  void proxyRebindCommitsAssignmentOnlyAfterHibernateThenPrefersSourceNodeForRestore() {
    var now = Instant.now();
    var workflow =
        io.browsercloud.persistence.SessionMigrationEntity.proxyRebind(
            "prb_1234567890abcdef",
            SESSION_ID,
            TENANT_ID,
            "node-a",
            7,
            "proxy-a",
            "pbind_source0000000001",
            "pbind_target0000000001",
            4,
            "admin-a",
            "Approved network change",
            "idem-rebind",
            "req-rebind",
            now);
    workflow.hibernateDispatched("op_hibernate00000001", now);
    var descriptor = mock(SessionDescriptor.class);
    when(descriptor.region()).thenReturn("singapore");
    var profile = mock(io.browsercloud.api.ProfileView.class);
    when(profile.latestCheckpointId()).thenReturn("checkpoint-a");
    var placement = mock(io.browsercloud.api.BrowserPlacementView.class);
    when(placement.nodeId()).thenReturn("node-a");
    var hibernated = session(SessionState.HIBERNATED, "node-a");
    var rebound = hibernated.withProxyBinding(null);
    var restored =
        session(SessionState.RUNNING, "node-a").nextContextEpoch("node-a", "runtime-a", 2);
    when(migrations.findById(workflow.getMigrationId())).thenReturn(Optional.of(workflow));
    when(sessions.require(SESSION_ID)).thenReturn(hibernated, restored);
    when(sessions.describe(SESSION_ID)).thenReturn(descriptor);
    when(profiles.get(TENANT_ID, "profile-a")).thenReturn(profile);
    when(proxies.commitRebindAfterHibernate(
            SESSION_ID,
            TENANT_ID,
            "pbind_target0000000001",
            4,
            "admin-a",
            "req-rebind",
            workflow.getMigrationId(),
            "singapore"))
        .thenReturn(rebound);
    when(capacity.reserveRestartTarget(rebound, "singapore", "node-a")).thenReturn(placement);
    when(sessionService.start(SESSION_ID, TENANT_ID, "system:proxy-rebind"))
        .thenReturn(new OperationResponse("op_restore000000001", OperationState.ACTIVE));

    service.reconcile(workflow.getMigrationId());

    assertThat(workflow.getPhase()).isEqualTo("RESTORING");
    assertThat(workflow.getTargetNodeId()).isEqualTo("node-a");
    assertThat(workflow.getCheckpointId()).isEqualTo("checkpoint-a");
    assertThat(workflow.getRestoreOperationId()).isEqualTo("op_restore000000001");
    var order = inOrder(proxies, capacity, sessionService);
    order
        .verify(proxies)
        .commitRebindAfterHibernate(
            SESSION_ID,
            TENANT_ID,
            "pbind_target0000000001",
            4,
            "admin-a",
            "req-rebind",
            workflow.getMigrationId(),
            "singapore");
    order.verify(capacity).reserveRestartTarget(rebound, "singapore", "node-a");
    order.verify(sessionService).start(SESSION_ID, TENANT_ID, "system:proxy-rebind");
  }

  @Test
  void migrationWaitsForTrustedProviderEvidenceWithoutResumingAgent() {
    var now = Instant.now();
    var migration =
        new io.browsercloud.persistence.SessionMigrationEntity(
            "mig_providerwait001", SESSION_ID, TENANT_ID, "node-a", 6, now);
    migration.targetPlaced("node-b", 7, "checkpoint-a", now);
    migration.stateResync("resync-a", now);
    migration.businessValidation(now);
    var state =
        new NodeEvent.StateUpdated(
            SESSION_ID,
            10,
            10,
            "https://crm.example.test/customers",
            "Customers",
            "hash",
            "COMPLETE",
            List.of());
    when(migrations.findById(migration.getMigrationId())).thenReturn(Optional.of(migration));
    when(browserStates.find(SESSION_ID))
        .thenReturn(Optional.of(new BrowserStateRepository.Snapshot(TENANT_ID, 7, state)));
    when(recoveryService.validateForMigration(SESSION_ID, TENANT_ID, migration.getMigrationId(), 0))
        .thenReturn(
            new BusinessRecoveryValidationView(
                "brv_providerwait0001",
                SESSION_ID,
                "crm",
                2L,
                7,
                10,
                Verdict.MANUAL_RECOVERY_REQUIRED,
                false,
                List.of("PROVIDER_EVIDENCE_MISSING:ACCOUNT:current-account:crm-provider"),
                "MIGRATION",
                migration.getMigrationId(),
                now));
    when(recoveryActions.request(any(), any())).thenReturn(false);

    service.reconcile(migration.getMigrationId());

    assertThat(migration.getPhase()).isEqualTo("BUSINESS_VALIDATION");
    assertThat(migration.getRecoveryResult()).isNull();
    verify(migrations, never()).save(migration);
    verify(resources, never())
        .recordMigrationPhase(
            eq(SESSION_ID),
            eq(migration.getMigrationId()),
            any(),
            any(),
            anyBoolean(),
            anyBoolean());
  }

  @Test
  void restoreFailureDispatchesFencedTargetCleanupOnlyOnceAcrossRepeatedReconcile() {
    var now = Instant.now();
    var migration =
        new io.browsercloud.persistence.SessionMigrationEntity(
            "mig_cleanupdispatch01", SESSION_ID, TENANT_ID, "node-a", 6, now);
    migration.targetPlaced("node-b", 7, "checkpoint-a", now);
    when(migrations.findById(migration.getMigrationId())).thenReturn(Optional.of(migration));
    when(sessions.require(SESSION_ID))
        .thenReturn(
            session(SessionState.FAILED, "node-b"), session(SessionState.HIBERNATING, "node-b"));
    when(sessionService.cleanupMigrationTarget(
            SESSION_ID, TENANT_ID, "TARGET_RESTORE_OPERATION_FAILED_OR_TIMED_OUT"))
        .thenReturn(new OperationResponse("op_cleanup00000001", OperationState.ACTIVE));

    service.reconcile(migration.getMigrationId());
    service.reconcile(migration.getMigrationId());

    assertThat(migration.getPhase()).isEqualTo("TARGET_CLEANUP");
    assertThat(migration.getTargetCleanupOperationId()).isEqualTo("op_cleanup00000001");
    assertThat(migration.getLastTargetFailureReason())
        .isEqualTo("TARGET_RESTORE_OPERATION_FAILED_OR_TIMED_OUT");
    verify(sessionService)
        .cleanupMigrationTarget(
            SESSION_ID, TENANT_ID, "TARGET_RESTORE_OPERATION_FAILED_OR_TIMED_OUT");
    verify(resources)
        .recordMigrationPhase(
            SESSION_ID,
            migration.getMigrationId(),
            "TARGET_CLEANUP",
            "TARGET_RESTORE_OPERATION_FAILED_OR_TIMED_OUT",
            false,
            false);
  }

  @Test
  void committedTargetCleanupPersistsFailedNodeBeforeRetryPlacement() {
    var now = Instant.now();
    var migration =
        new io.browsercloud.persistence.SessionMigrationEntity(
            "mig_cleanupretry0001", SESSION_ID, TENANT_ID, "node-a", 6, now);
    migration.targetPlaced("node-b", 7, "checkpoint-a", now);
    migration.targetCleanupDispatched(
        "op_cleanup00000001", "TARGET_RESTORE_OPERATION_FAILED_OR_TIMED_OUT", now);
    when(migrations.findById(migration.getMigrationId())).thenReturn(Optional.of(migration));
    when(sessions.require(SESSION_ID)).thenReturn(session(SessionState.HIBERNATED, "node-b"));

    service.reconcile(migration.getMigrationId());

    assertThat(migration.getPhase()).isEqualTo("PLACING_TARGET");
    assertThat(migration.getFailedTargetNodeIds()).isEqualTo("[\"node-b\"]");
    assertThat(migration.getTargetAttempt()).isEqualTo(1);
    assertThat(migration.getTargetCleanupOperationId()).isEqualTo("op_cleanup00000001");
    verify(resources)
        .recordMigrationPhase(
            SESSION_ID,
            migration.getMigrationId(),
            "PLACING_TARGET",
            "RETRY_AFTER_COMMITTED_TARGET_CLEANUP",
            false,
            false);
  }

  @Test
  void targetRestoreRetriesAreBoundedAndFailClosedAfterCleanup() {
    var now = Instant.now();
    var migration =
        new io.browsercloud.persistence.SessionMigrationEntity(
            "mig_retryexhaust0001", SESSION_ID, TENANT_ID, "node-a", 6, now);
    migration.targetPlaced("node-b", 7, "checkpoint-a", now);
    migration.targetCleanupCommitted("[\"node-b\"]", now);
    migration.targetRetryReady("[\"node-b\"]", now);
    migration.targetPlaced("node-c", 8, "checkpoint-a", now);
    migration.targetCleanupCommitted("[\"node-b\",\"node-c\"]", now);
    migration.targetRetryReady("[\"node-b\",\"node-c\"]", now);
    migration.targetPlaced("node-d", 9, "checkpoint-a", now);
    migration.targetCleanupDispatched(
        "op_cleanup00000003", "TARGET_RESTORE_OPERATION_FAILED_OR_TIMED_OUT", now);
    when(migrations.findById(migration.getMigrationId())).thenReturn(Optional.of(migration));
    when(sessions.require(SESSION_ID)).thenReturn(session(SessionState.HIBERNATED, "node-d"));

    service.reconcile(migration.getMigrationId());

    assertThat(migration.getPhase()).isEqualTo("FAILED");
    assertThat(migration.getFailureReason()).isEqualTo("TARGET_RESTORE_RETRY_EXHAUSTED");
    assertThat(migration.getFailedTargetNodeIds()).isEqualTo("[\"node-b\",\"node-c\",\"node-d\"]");
    verify(resources)
        .recordMigrationPhase(
            SESSION_ID,
            migration.getMigrationId(),
            "FAILED",
            "TARGET_RESTORE_RETRY_EXHAUSTED",
            true,
            false);
  }

  private static SessionContext session() {
    return session(SessionState.RUNNING, "node-a");
  }

  private static SessionContext session(SessionState state, String nodeId) {
    var now = Instant.now();
    return new SessionContext(
        SESSION_ID,
        TENANT_ID,
        "profile-a",
        nodeId,
        "runtime-a",
        "isolation-a",
        "proxy-a",
        3,
        7,
        1,
        1,
        ResourceClass.L2,
        state,
        "policy-hash",
        now,
        now);
  }
}
