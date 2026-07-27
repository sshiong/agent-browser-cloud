package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.SafePointModels.NodeSafetyObservation;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
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
import org.mockito.ArgumentCaptor;

class SafePointApplicationServiceTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final BrowserPlacementJpaRepository placements =
      mock(BrowserPlacementJpaRepository.class);
  private final BrowserNodeJpaRepository browserNodes = mock(BrowserNodeJpaRepository.class);
  private final SessionSafetySignalJpaRepository signals =
      mock(SessionSafetySignalJpaRepository.class);
  private final ExclusiveOperationJpaRepository operations =
      mock(ExclusiveOperationJpaRepository.class);
  private final AgentTaskJpaRepository tasks = mock(AgentTaskJpaRepository.class);
  private final DurableWorkflowJpaRepository workflows = mock(DurableWorkflowJpaRepository.class);
  private final SafePointApplicationService service =
      new SafePointApplicationService(
          sessions,
          placements,
          browserNodes,
          signals,
          operations,
          tasks,
          workflows,
          new ObjectMapper());

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
        new NodeSafetyObservation(true, true, 2, 1, null, null, null, now));

    verify(signals, org.mockito.Mockito.times(2)).save(any(SessionSafetySignalEntity.class));
  }

  @Test
  void capableNodeRequiresCompleteBrowserActivitySignals() {
    advertiseBrowserActivityCapability();
    var now = Instant.now();
    when(signals.findAllBySessionId("ses_1234567890abcdef"))
        .thenReturn(List.of(signal("ACTIVE_INPUT", false, now), signal("ACTIVE_DRAG", false, now)));

    var result = service.assess("ses_1234567890abcdef", "tenant-a");

    assertThat(result.safe()).isFalse();
    assertThat(result.state()).isEqualTo("UNKNOWN");
    assertThat(result.dataFreshness()).isEqualTo("MISSING");
    assertThat(result.blockers().getFirst().detail())
        .contains("FILE_UPLOAD_ACTIVE", "FILE_DOWNLOAD_ACTIVE", "FORM_SUBMISSION_ACTIVE");
  }

  @Test
  void activeUploadFromCapableNodeBlocksMigration() {
    advertiseBrowserActivityCapability();
    var now = Instant.now();
    when(signals.findAllBySessionId("ses_1234567890abcdef"))
        .thenReturn(
            List.of(
                signal("ACTIVE_INPUT", false, now),
                signal("ACTIVE_DRAG", false, now),
                browserActivitySignal("FILE_UPLOAD_ACTIVE", true, now),
                browserActivitySignal("FILE_DOWNLOAD_ACTIVE", false, now),
                browserActivitySignal("FORM_SUBMISSION_ACTIVE", false, now)));

    var result = service.assess("ses_1234567890abcdef", "tenant-a");

    assertThat(result.safe()).isFalse();
    assertThat(result.state()).isEqualTo("BLOCKED");
    assertThat(result.dataFreshness()).isEqualTo("LIVE");
    assertThat(result.blockers())
        .extracting(blocker -> blocker.code())
        .containsExactly("FILE_UPLOAD_ACTIVE");
  }

  @Test
  void browserActivityObservationPersistsThreeSignals() {
    var now = Instant.now();
    when(signals.findBySessionIdAndSignalTypeAndSource(any(), any(), any()))
        .thenReturn(Optional.empty());

    service.recordNodeObservation(
        "ses_1234567890abcdef",
        "tenant-a",
        "node-a",
        7,
        new NodeSafetyObservation(null, null, null, null, 1, 2, 1, now));

    var captor = ArgumentCaptor.forClass(SessionSafetySignalEntity.class);
    verify(signals, org.mockito.Mockito.times(3)).save(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(
            signal -> {
              try {
                var details = new ObjectMapper().readTree(signal.getDetails());
                assertThat(details.get("activeUploadCount").asInt()).isEqualTo(1);
                assertThat(details.get("activeDownloadCount").asInt()).isEqualTo(2);
                assertThat(details.get("activeFormSubmissionCount").asInt()).isEqualTo(1);
              } catch (Exception exception) {
                throw new AssertionError("activity details must be valid JSON", exception);
              }
            });
  }

  private void advertiseBrowserActivityCapability() {
    var node = mock(BrowserNodeEntity.class);
    when(node.getLabels())
        .thenReturn(
            "{\"safePointBrowserActivity\":\"cdp-network-v1\","
                + "\"resourceEnforcement\":\"cgroup-v2\"}");
    when(browserNodes.findById("node-a")).thenReturn(Optional.of(node));
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

  private static SessionSafetySignalEntity browserActivitySignal(
      String type, boolean active, Instant observedAt) {
    return new SessionSafetySignalEntity(
        "signal-" + type,
        "ses_1234567890abcdef",
        "tenant-a",
        "node-a",
        7,
        type,
        SafePointApplicationService.NODE_BROWSER_ACTIVITY_SOURCE,
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
