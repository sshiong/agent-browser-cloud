package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.StateResyncRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class StateResyncAdmissionServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

  @Mock private JdbcTemplate jdbc;
  @Mock private AuditApplicationService auditService;

  private StateResyncAdmissionService service;

  @BeforeEach
  void setUp() {
    service =
        spy(
            new StateResyncAdmissionService(
                jdbc, auditService, 300, 60, 600, 60, 30, 7, Clock.fixed(NOW, ZoneOffset.UTC)));
    lenient()
        .doReturn(Optional.of(availableContext()))
        .when(service)
        .loadBudgetContext(anyString(), anyString());
  }

  @Test
  void recordsWeightedRegionAdmissionWithoutPersistingTheRawRootReference() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(20, 10);
    allowAllDimensionalBudgets();
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    service.admitUser(
        "tenant-test",
        "ses_1234567890abcdef",
        "operator-test",
        "cmd_1234567890abcdef",
        StateResyncRequest.Mode.REGION,
        "#payment-form",
        "USER_REQUEST");

    var values = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(anyString(), values.capture());
    assertThat(values.getValue()[3]).isEqualTo("REGION");
    assertThat(values.getValue()[4]).isEqualTo("USER");
    assertThat(values.getValue()[6])
        .asString()
        .matches("^[0-9a-f]{64}$")
        .isNotEqualTo("#payment-form");
    assertThat(values.getValue()[7]).isEqualTo(StateResyncAdmissionService.REGION_TOKEN_COST);
    assertThat(values.getValue()[9]).isEqualTo("node-test");
    assertThat(values.getValue()[10]).isEqualTo("region-test");
    assertThat(values.getValue()[11]).isEqualTo(100);
    assertThat(values.getValue()[13]).isEqualTo(StateResyncAdmissionService.REGION_RESERVED_BYTES);
    assertThat(values.getValue()[15])
        .isEqualTo(StateResyncAdmissionService.REGION_RESERVED_CPU_MILLIS);
  }

  @Test
  void rejectsAUserRequestBeforeWritingWhenTheSessionWindowIsExhausted() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(100, 60);

    assertThatThrownBy(
            () ->
                service.admitUser(
                    "tenant-test",
                    "ses_1234567890abcdef",
                    "operator-test",
                    "cmd_1234567890abcdef",
                    StateResyncRequest.Mode.FULL,
                    "",
                    "USER_REQUEST"))
        .isInstanceOf(StateResyncAdmissionService.StateResyncBudgetExceededException.class)
        .hasMessageContaining("SESSION");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(auditService)
        .appendIndependent(
            org.mockito.ArgumentMatchers.argThat(
                record ->
                    record.eventType().equals("STATE_RESYNC_BUDGET_REJECTED")
                        && record.actorId().equals("operator-test")
                        && record.result().equals("BLOCKED")));
  }

  @Test
  void opensTheAutomaticCircuitWithoutThrowingOrPersistingAnotherRequest() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(100, 20, 30);
    allowAllDimensionalBudgets();

    var decision =
        service.tryAdmitAutomaticFull(
            "tenant-test",
            "ses_1234567890abcdef",
            "cmd_1234567890abcdef",
            "AUTO_BACKPRESSURE_LIMIT");

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.scope()).isEqualTo("AUTOMATIC_CIRCUIT");
    assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void rejectsWhenTheSessionByteReservationWouldExceedItsWindow() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0, 0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(0L, 4L * 1024 * 1024);

    assertThatThrownBy(
            () ->
                service.admitUser(
                    "tenant-test",
                    "ses_1234567890abcdef",
                    "operator-test",
                    "cmd_1234567890abcdef",
                    StateResyncRequest.Mode.FULL,
                    "",
                    "USER_REQUEST"))
        .isInstanceOf(StateResyncAdmissionService.StateResyncBudgetExceededException.class)
        .hasMessageContaining("SESSION_BYTES");

    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void rejectsWhenTheOwningNodeByteReservationWouldExceedCertifiedCapacity() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0, 0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(0L, 0L, 0L, 0L, 32L * 1024 * 1024);

    assertThatThrownBy(
            () ->
                service.admitUser(
                    "tenant-test",
                    "ses_1234567890abcdef",
                    "operator-test",
                    "cmd_1234567890abcdef",
                    StateResyncRequest.Mode.FULL,
                    "",
                    "USER_REQUEST"))
        .isInstanceOf(StateResyncAdmissionService.StateResyncBudgetExceededException.class)
        .hasMessageContaining("NODE_BYTES");

    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void appliesReducedCapacityWeightsToSecondaryAndDisasterRecoveryRegions() {
    var secondary =
        new StateResyncAdmissionService.BudgetContext(
            "node-secondary",
            "region-secondary",
            4_000,
            "READY",
            "OPEN",
            "NORMAL",
            NOW,
            16_384,
            "SECONDARY",
            "OPEN");
    var disasterRecovery =
        new StateResyncAdmissionService.BudgetContext(
            "node-dr", "region-dr", 4_000, "READY", "OPEN", "NORMAL", NOW, 16_384, "DR", "OPEN");

    assertThat(secondary.regionWeightPercent()).isEqualTo(75);
    assertThat(disasterRecovery.regionWeightPercent()).isEqualTo(50);
  }

  @Test
  void rejectsIfThePlacementChangesAfterAdmissionLocksAreAcquired() {
    var moved =
        new StateResyncAdmissionService.BudgetContext(
            "node-moved",
            "region-test",
            4_000,
            "READY",
            "OPEN",
            "NORMAL",
            NOW,
            16_384,
            "PRIMARY",
            "OPEN");
    doReturn(Optional.of(availableContext()), Optional.of(moved))
        .when(service)
        .loadBudgetContext(anyString(), anyString());

    assertThatThrownBy(
            () ->
                service.admitUser(
                    "tenant-test",
                    "ses_1234567890abcdef",
                    "operator-test",
                    "cmd_1234567890abcdef",
                    StateResyncRequest.Mode.FULL,
                    "",
                    "USER_REQUEST"))
        .isInstanceOf(StateResyncAdmissionService.StateResyncBudgetExceededException.class)
        .hasMessageContaining("NODE_CAPACITY");

    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void settlesReservationsWithMeasuredNodeConsumption() {
    doReturn(
            Optional.of(
                new StateResyncAdmissionService.LedgerContext(
                    "tenant-test",
                    "ses_1234567890abcdef",
                    "FULL",
                    "node-test",
                    "region-test",
                    StateResyncAdmissionService.FULL_RESERVED_CPU_MILLIS)))
        .when(service)
        .loadLedgerContext("cmd_1234567890abcdef");
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    service.settleActual(
        "tenant-test",
        "ses_1234567890abcdef",
        "cmd_1234567890abcdef",
        StateResyncRequest.Mode.FULL,
        48_000,
        37L);

    var values = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(anyString(), values.capture());
    assertThat(values.getValue()[0]).isEqualTo(48_000L);
    assertThat(values.getValue()[1]).isEqualTo(37L);
    verify(auditService)
        .append(
            org.mockito.ArgumentMatchers.argThat(
                record ->
                    record.eventType().equals("STATE_RESYNC_BUDGET_SETTLED")
                        && record.details().get("cpuMeasurement").equals("BROWSER_CGROUP")
                        && record.requestId().equals("cmd_1234567890abcdef")));
  }

  private void allowAllDimensionalBudgets() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(0L, 0L, 0L, 0L, 0L, 0L, 4_000L, 0L, 0L);
  }

  private StateResyncAdmissionService.BudgetContext availableContext() {
    return new StateResyncAdmissionService.BudgetContext(
        "node-test",
        "region-test",
        4_000,
        "READY",
        "OPEN",
        "NORMAL",
        NOW,
        16_384,
        "PRIMARY",
        "OPEN");
  }
}
