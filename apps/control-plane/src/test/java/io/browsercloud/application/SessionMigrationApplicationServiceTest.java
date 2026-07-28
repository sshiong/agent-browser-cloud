package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.browsercloud.api.OperationResponse;
import io.browsercloud.api.SafePointModels.SessionSafePointView;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionMigrationJpaRepository;
import java.time.Instant;
import java.util.List;
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
  private final BusinessRecoveryValidator recoveryValidator = mock(BusinessRecoveryValidator.class);
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
          recoveryValidator,
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
