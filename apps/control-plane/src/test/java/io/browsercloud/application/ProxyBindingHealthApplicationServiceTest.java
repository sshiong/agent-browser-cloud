package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.application.ProxyBindingHealthApplicationService.NodeProbeObservation;
import io.browsercloud.application.ProxyBindingHealthApplicationService.ProxyHealthRejectedException;
import io.browsercloud.persistence.ProxyAllocationEntity;
import io.browsercloud.persistence.ProxyAllocationJpaRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ProxyBindingHealthApplicationServiceTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private ProxyAllocationJpaRepository allocations;

  private ProxyBindingHealthApplicationService service;

  @BeforeEach
  void setUp() {
    service = new ProxyBindingHealthApplicationService(jdbc, allocations, 7);
  }

  @Test
  void observedExitMismatchIsPersistedAsABoundedFailure() {
    var allocation = activeProfileAllocation();
    when(allocations.findFirstBySessionIdAndStateIn(anyString(), any()))
        .thenReturn(Optional.of(allocation));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    service.recordNodeProbe(
        "ses_test",
        "tenant-test",
        "node_test",
        new NodeProbeObservation(true, 91, "198.51.100.7", ""),
        Instant.parse("2026-08-01T00:00:00Z"));

    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), arguments.capture());
    var inserted = arguments.getAllValues().getFirst();
    assertThat(inserted[6]).isEqualTo("ACTIVE_EXIT_PROBE");
    assertThat(inserted[7]).isEqualTo(false);
    assertThat(inserted[9]).isNull();
    assertThat(inserted[10]).isEqualTo("EXIT_MISMATCH");
  }

  @Test
  void arbitraryNodeFailureTextCannotEnterTheHealthLedger() {
    when(allocations.findFirstBySessionIdAndStateIn(anyString(), any()))
        .thenReturn(Optional.of(activeProfileAllocation()));

    assertThatThrownBy(
            () ->
                service.recordNodeProbe(
                    "ses_test",
                    "tenant-test",
                    "node_test",
                    new NodeProbeObservation(false, 12, null, "password=secret"),
                    Instant.parse("2026-08-01T00:00:00Z")))
        .isInstanceOf(ProxyHealthRejectedException.class)
        .hasMessage("INVALID_PROXY_PROBE_RESULT");

    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void systemManagedAllocationDoesNotCreateTenantBindingHealth() {
    var allocation =
        new ProxyAllocationEntity(
            "pxy_test",
            "tenant-test",
            "ses_test",
            "static-test",
            "http://127.0.0.1:8081",
            Instant.parse("2026-08-01T00:00:00Z"));
    allocation.bind("203.0.113.10", "TEST", "AS64500", Instant.parse("2026-08-01T00:00:01Z"));
    when(allocations.findFirstBySessionIdAndStateIn(anyString(), any()))
        .thenReturn(Optional.of(allocation));

    service.recordNodeProbe(
        "ses_test",
        "tenant-test",
        "node_test",
        new NodeProbeObservation(true, 17, "203.0.113.10", ""),
        Instant.parse("2026-08-01T00:00:02Z"));

    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  private static ProxyAllocationEntity activeProfileAllocation() {
    var allocation =
        new ProxyAllocationEntity(
            "pxy_test",
            "tenant-test",
            "ses_test",
            "static-test",
            "http://127.0.0.1:8081",
            "pbind_1234567890123456",
            0L,
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            Instant.parse("2026-08-01T00:00:00Z"));
    allocation.bind("203.0.113.10", "TEST", "AS64500", Instant.parse("2026-08-01T00:00:01Z"));
    return allocation;
  }
}
