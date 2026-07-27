package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.SafePointModels.NodeSafetyObservation;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.BrowserPlacementEntity;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.DurableWorkflowJpaRepository;
import io.browsercloud.persistence.SessionSafetySignalEntity;
import io.browsercloud.persistence.SessionSafetySignalJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SafePointApplicationServiceTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final BrowserPlacementJpaRepository placements =
      mock(BrowserPlacementJpaRepository.class);
  private final SessionSafetySignalJpaRepository signals =
      mock(SessionSafetySignalJpaRepository.class);
  private final ExclusiveOperationJpaRepository operations =
      mock(ExclusiveOperationJpaRepository.class);
  private final AgentTaskJpaRepository tasks = mock(AgentTaskJpaRepository.class);
  private final DurableWorkflowJpaRepository workflows = mock(DurableWorkflowJpaRepository.class);
  private final SafePointApplicationService service =
      new SafePointApplicationService(sessions, placements, signals, operations, tasks, workflows);

  @BeforeEach
  void setUp() {
    when(sessions.require("ses_1234567890abcdef")).thenReturn(session());
    var placement = mock(BrowserPlacementEntity.class);
    when(placement.getState()).thenReturn("ACTIVE");
    when(placements.findById("ses_1234567890abcdef")).thenReturn(Optional.of(placement));
    when(operations.findBySessionIdAndState("ses_1234567890abcdef", "ACTIVE"))
        .thenReturn(Optional.empty());
    when(tasks.findAllBySessionIdAndStateIn(any(), any())).thenReturn(List.of());
    when(workflows.findAllBySessionIdAndStateIn(any(), any())).thenReturn(List.of());
  }

  @Test
  void missingNodeSignalsRemainUnknownAndUnsafe() {
    when(signals.findAllBySessionId("ses_1234567890abcdef")).thenReturn(List.of());

    var result = service.assess("ses_1234567890abcdef", "tenant-a");

    assertThat(result.safe()).isFalse();
    assertThat(result.state()).isEqualTo("UNKNOWN");
    assertThat(result.dataFreshness()).isEqualTo("MISSING");
    assertThat(result.blockers())
        .extracting(blocker -> blocker.code())
        .containsExactly("NODE_SAFETY_SIGNAL_MISSING");
  }

  @Test
  void freshInactiveInputLedgerAllowsSafePoint() {
    var now = Instant.now();
    when(signals.findAllBySessionId("ses_1234567890abcdef"))
        .thenReturn(List.of(signal("ACTIVE_INPUT", false, now), signal("ACTIVE_DRAG", false, now)));

    var result = service.assess("ses_1234567890abcdef", "tenant-a");

    assertThat(result.safe()).isTrue();
    assertThat(result.state()).isEqualTo("SAFE");
    assertThat(result.dataFreshness()).isEqualTo("LIVE");
    assertThat(result.blockers()).isEmpty();
  }

  @Test
  void activeInputIsAnExplicitBlocker() {
    var now = Instant.now();
    when(signals.findAllBySessionId("ses_1234567890abcdef"))
        .thenReturn(List.of(signal("ACTIVE_INPUT", true, now), signal("ACTIVE_DRAG", false, now)));

    var result = service.assess("ses_1234567890abcdef", "tenant-a");

    assertThat(result.safe()).isFalse();
    assertThat(result.state()).isEqualTo("BLOCKED");
    assertThat(result.blockers()).extracting(blocker -> blocker.code()).contains("ACTIVE_INPUT");
  }

  @Test
  void nodeObservationPersistsBothInputLedgerSignals() {
    var now = Instant.now();
    when(signals.findBySessionIdAndSignalTypeAndSource(any(), any(), any()))
        .thenReturn(Optional.empty());

    service.recordNodeObservation(
        "ses_1234567890abcdef",
        "tenant-a",
        "node-a",
        7,
        new NodeSafetyObservation(true, true, 2, 1, now));

    verify(signals, org.mockito.Mockito.times(2)).save(any(SessionSafetySignalEntity.class));
  }

  private static SessionSafetySignalEntity signal(String type, boolean active, Instant observedAt) {
    return new SessionSafetySignalEntity(
        "signal-" + type,
        "ses_1234567890abcdef",
        "tenant-a",
        "node-a",
        7,
        type,
        SafePointApplicationService.NODE_INPUT_SOURCE,
        active,
        "{}",
        observedAt,
        observedAt.plusSeconds(15),
        observedAt);
  }

  private static SessionContext session() {
    var now = Instant.now();
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-a",
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
