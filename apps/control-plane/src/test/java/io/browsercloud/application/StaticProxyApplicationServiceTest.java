package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
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

@ExtendWith(MockitoExtension.class)
class StaticProxyApplicationServiceTest {

  @Mock private ProxyAllocationJpaRepository repository;
  @Mock private SessionRepository sessionRepository;

  private StaticProxyApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new StaticProxyApplicationService(
            repository,
            sessionRepository,
            "static-test",
            "http://127.0.0.1:8081",
            "203.0.113.10",
            false,
            "test");
  }

  @Test
  void shouldAllocateProxyAndAdvanceContextIdentityBeforeRuntimeStart() {
    var session = session();
    when(repository.findFirstBySessionIdAndStateIn(any(), any())).thenReturn(Optional.empty());

    var bound = service.ensureBinding(session);

    assertThat(bound.proxyBindingId()).startsWith("pxy_");
    assertThat(bound.contextEpoch()).isEqualTo(4);
    assertThat(bound.networkRevision()).isEqualTo(8);
    var allocation = ArgumentCaptor.forClass(ProxyAllocationEntity.class);
    verify(repository).save(allocation.capture());
    assertThat(allocation.getValue().getTenantId()).isEqualTo("tenant-test");
    assertThat(allocation.getValue().getSessionId()).isEqualTo("ses_test");
    assertThat(allocation.getValue().getState()).isEqualTo("ALLOCATED");
    verify(sessionRepository).updateWithExpectedEpoch(bound, 3);
  }

  @Test
  void shouldBindOnlyWhenNodeObservedExpectedExit() {
    var allocation =
        new ProxyAllocationEntity(
            "pxy_test",
            "tenant-test",
            "ses_test",
            "static-test",
            "http://127.0.0.1:8081",
            Instant.parse("2026-07-26T00:00:00Z"));
    when(repository.findById("pxy_test")).thenReturn(Optional.of(allocation));

    service.recordBound(
        "tenant-test",
        new NodeEvent.RuntimeStarted(
            "ses_test",
            "node-test",
            "runtime-test",
            42,
            1,
            "http://127.0.0.1:9222",
            "pxy_test",
            "203.0.113.10",
            "ZZ",
            "AS64500"));

    assertThat(allocation.getState()).isEqualTo("BOUND");
    assertThat(allocation.getExitIp()).isEqualTo("203.0.113.10");
    assertThat(allocation.getVerifiedAt()).isNotNull();
  }

  @Test
  void shouldRejectMismatchedObservedExit() {
    var allocation =
        new ProxyAllocationEntity(
            "pxy_test",
            "tenant-test",
            "ses_test",
            "static-test",
            "http://127.0.0.1:8081",
            Instant.parse("2026-07-26T00:00:00Z"));
    when(repository.findById("pxy_test")).thenReturn(Optional.of(allocation));

    assertThatThrownBy(
            () ->
                service.recordBound(
                    "tenant-test",
                    new NodeEvent.RuntimeStarted(
                        "ses_test",
                        "node-test",
                        "runtime-test",
                        42,
                        1,
                        "http://127.0.0.1:9222",
                        "pxy_test",
                        "198.51.100.7",
                        "ZZ",
                        "AS64500")))
        .isInstanceOf(StaticProxyApplicationService.ProxyUnavailableException.class)
        .hasMessageContaining("does not match");
    assertThat(allocation.getState()).isEqualTo("ALLOCATED");
  }

  @Test
  void shouldForbidDirectFallbackInProduction() {
    assertThatThrownBy(
            () ->
                new StaticProxyApplicationService(
                    repository, sessionRepository, "static-test", "", "", true, "production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be enabled");
  }

  private static SessionContext session() {
    var now = Instant.parse("2026-07-26T00:00:00Z");
    return new SessionContext(
        "ses_test",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-test",
        null,
        null,
        1,
        3,
        2,
        7,
        ResourceClass.L2,
        SessionState.CREATED,
        "",
        now,
        now);
  }
}
