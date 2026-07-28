package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.BusinessRecoveryModels.BusinessRecoveryValidationView;
import io.browsercloud.api.BusinessRecoveryModels.RecoveryAction;
import io.browsercloud.api.BusinessRecoveryModels.Verdict;
import io.browsercloud.application.ApplicationBusinessRecoveryService.AutoRecoveryPolicy;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.BusinessRecoveryActionEntity;
import io.browsercloud.persistence.BusinessRecoveryActionJpaRepository;
import io.browsercloud.persistence.SessionMigrationEntity;
import io.browsercloud.persistence.SessionMigrationJpaRepository;
import io.browsercloud.proto.node.v1.BusinessRecoveryActionCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusinessRecoveryActionApplicationServiceTest {

  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String TENANT_ID = "tenant-a";

  private final ApplicationBusinessRecoveryService recovery =
      mock(ApplicationBusinessRecoveryService.class);
  private final BusinessRecoveryActionJpaRepository actions =
      mock(BusinessRecoveryActionJpaRepository.class);
  private final SessionMigrationJpaRepository migrations =
      mock(SessionMigrationJpaRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final BrowserStateRepository browserStates = mock(BrowserStateRepository.class);
  private final BrowserCapacityApplicationService capacity =
      mock(BrowserCapacityApplicationService.class);
  private final NodeCommandGateway nodeCommands = mock(NodeCommandGateway.class);
  private final SessionResourceApplicationService resources =
      mock(SessionResourceApplicationService.class);
  private final BusinessRecoveryActionApplicationService service =
      new BusinessRecoveryActionApplicationService(
          recovery,
          actions,
          migrations,
          sessions,
          browserStates,
          capacity,
          nodeCommands,
          resources);

  @Test
  void dispatchesBoundedReloadAndCommitsOnlyAfterStateAcknowledgement() throws Exception {
    var now = Instant.now();
    var migration =
        new SessionMigrationEntity("mig_1234567890abcdef", SESSION_ID, TENANT_ID, "node-a", 6, now);
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
    when(recovery.autoRecoveryPolicy(SESSION_ID, TENANT_ID))
        .thenReturn(
            Optional.of(
                new AutoRecoveryPolicy(
                    "arc_1234567890abcdefghij",
                    1,
                    RecoveryAction.RELOAD,
                    1,
                    List.of("https://crm.example.test"),
                    List.of("/customers"))));
    when(actions.countByMigrationId(migration.getMigrationId())).thenReturn(0L);
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session());
    when(browserStates.find(SESSION_ID))
        .thenReturn(Optional.of(new BrowserStateRepository.Snapshot(TENANT_ID, 7, state)));
    when(capacity.nodeHasCapability("node-b", "businessRecoveryActions", "cdp-low-risk-v1"))
        .thenReturn(true);
    when(actions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var validation =
        new BusinessRecoveryValidationView(
            "brv_1234567890abcdefghij",
            SESSION_ID,
            "crm",
            1L,
            7,
            10,
            Verdict.STATE_CHANGED,
            false,
            List.of("REQUIRED_TARGETS_MISSING:button:Continue"),
            "MIGRATION",
            migration.getMigrationId(),
            now);

    assertThat(service.request(migration, validation)).isTrue();
    assertThat(migration.getPhase()).isEqualTo("BUSINESS_RECOVERY_ACTION");

    var commandCaptor = ArgumentCaptor.forClass(NodeCommand.class);
    verify(nodeCommands).send(commandCaptor.capture());
    var command = commandCaptor.getValue();
    assertThat(command.commandType()).isEqualTo("BusinessRecoveryAction");
    var payload = BusinessRecoveryActionCommand.parseFrom(command.payload());
    assertThat(payload.getAction()).isEqualTo("RELOAD");
    assertThat(payload.getTargetUrl()).isEmpty();
    assertThat(payload.getBaseStateVersion()).isEqualTo(10);

    var actionCaptor = ArgumentCaptor.forClass(BusinessRecoveryActionEntity.class);
    verify(actions, org.mockito.Mockito.atLeastOnce()).save(actionCaptor.capture());
    var action = actionCaptor.getValue();
    assertThat(action.getState()).isEqualTo("EXECUTING");
    when(actions.findById(action.getActionId())).thenReturn(Optional.of(action));
    when(migrations.findById(migration.getMigrationId())).thenReturn(Optional.of(migration));

    var acknowledgedState =
        new NodeEvent.StateUpdated(
            SESSION_ID,
            11,
            11,
            "https://crm.example.test/customers",
            "Customers",
            "hash-2",
            "COMPLETE",
            List.of(),
            "BUSINESS_RECOVERY_ACTION",
            action.getActionId());
    assertThatThrownBy(
            () ->
                service.stateUpdated(
                    new NodeEventReceived(
                        "evt_stale_action", TENANT_ID, SESSION_ID, 3, 8, 0, 1, acknowledgedState),
                    acknowledgedState))
        .isInstanceOf(BusinessRecoveryActionApplicationService.AutoRecoveryRejectedException.class)
        .hasMessage("AUTO_RECOVERY_ACK_CONTEXT_MISMATCH");
    assertThat(action.getState()).isEqualTo("EXECUTING");

    service.stateUpdated(
        new NodeEventReceived("evt_action", TENANT_ID, SESSION_ID, 3, 7, 0, 1, acknowledgedState),
        acknowledgedState);

    assertThat(action.getState()).isEqualTo("COMMITTED");
    assertThat(action.getResultingStateVersion()).isEqualTo(11);
    assertThat(migration.getPhase()).isEqualTo("BUSINESS_VALIDATION");
  }

  private static SessionContext session() {
    var now = Instant.now();
    return new SessionContext(
        SESSION_ID,
        TENANT_ID,
        "profile-a",
        "node-b",
        "runtime-a",
        "isolation-a",
        "proxy-a",
        3,
        7,
        2,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
