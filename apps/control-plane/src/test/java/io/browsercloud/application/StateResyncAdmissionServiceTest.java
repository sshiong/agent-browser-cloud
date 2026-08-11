package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.StateResyncRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
        new StateResyncAdmissionService(
            jdbc, auditService, 300, 60, 600, 60, 30, 7, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void recordsWeightedRegionAdmissionWithoutPersistingTheRawRootReference() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(20, 10);
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
}
