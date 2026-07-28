package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.BusinessRecoveryModels.BusinessRecoveryValidationView;
import io.browsercloud.api.BusinessRecoveryModels.Verdict;
import io.browsercloud.api.OperationResponse;
import io.browsercloud.api.SafePointModels.SessionSafePointView;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
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
          resources);

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

  private static SessionContext session() {
    var now = Instant.now();
    return new SessionContext(
        SESSION_ID,
        TENANT_ID,
        "profile-a",
        "node-a",
        "runtime-a",
        "isolation-a",
        "proxy-a",
        3,
        7,
        1,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
