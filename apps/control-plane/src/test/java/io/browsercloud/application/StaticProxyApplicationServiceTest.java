package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.ProxyBindingModels.ProxyBindingRequest;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.ProxyAllocationEntity;
import io.browsercloud.persistence.ProxyAllocationJpaRepository;
import io.browsercloud.persistence.ProxyBindingProfileEntity;
import io.browsercloud.persistence.ProxyBindingProfileJpaRepository;
import io.browsercloud.persistence.SessionProxyBindingAssignmentEntity;
import io.browsercloud.persistence.SessionProxyBindingAssignmentJpaRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaticProxyApplicationServiceTest {

  @TempDir Path tempDir;

  @Mock private ProxyAllocationJpaRepository repository;
  @Mock private ProxyBindingProfileJpaRepository bindingProfiles;
  @Mock private SessionProxyBindingAssignmentJpaRepository bindingAssignments;
  @Mock private SessionRepository sessionRepository;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;
  @Mock private ProxyRouteLearningApplicationService routeLearning;

  private StaticProxyApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new StaticProxyApplicationService(
            repository,
            bindingProfiles,
            bindingAssignments,
            sessionRepository,
            idempotency,
            audit,
            "static-test",
            "http://127.0.0.1:8081",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
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
    assertThat(allocation.getValue().getProviderEndpointId())
        .isEqualTo(allocation.getValue().getAllocationId());
    assertThat(allocation.getValue().getProviderAdapterType()).isEqualTo("CONFIGURED_HTTP");
    verify(sessionRepository).updateWithExpectedEpoch(bound, 3);
  }

  @Test
  void shouldReplaceReleasedRuntimeBindingFromDurableAssignmentOnRestart() {
    var released =
        new ProxyAllocationEntity(
            "pxy_released",
            "tenant-test",
            "ses_test",
            "static-test",
            "http://127.0.0.1:8081",
            Instant.parse("2026-07-26T00:00:00Z"));
    released.release(Instant.parse("2026-07-26T00:01:00Z"));
    var session = session().withProxyBinding("pxy_released");
    when(repository.findById("pxy_released")).thenReturn(Optional.of(released));
    when(repository.findFirstBySessionIdAndStateIn(any(), any())).thenReturn(Optional.empty());

    var rebound = service.ensureBinding(session);

    assertThat(rebound.proxyBindingId()).startsWith("pxy_").isNotEqualTo("pxy_released");
    assertThat(rebound.contextEpoch()).isEqualTo(session.contextEpoch() + 1);
    var replacement = ArgumentCaptor.forClass(ProxyAllocationEntity.class);
    verify(repository).save(replacement.capture());
    assertThat(replacement.getValue().getAllocationId()).isEqualTo(rebound.proxyBindingId());
    assertThat(replacement.getValue().getState()).isEqualTo("ALLOCATED");
    verify(sessionRepository).updateWithExpectedEpoch(rebound, session.contextEpoch());
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
  void shouldFailClosedInsteadOfFalselyReleasingAnUnknownDynamicAdapter() {
    var allocation =
        new ProxyAllocationEntity(
            "pxy_dynamic",
            "tenant-test",
            "ses_test",
            "dynamic-provider",
            "http://127.0.0.1:8082",
            "vendor-endpoint-1",
            "REMOTE_WORKER",
            null,
            null,
            "203.0.113.10",
            "vault://tenant-test/proxy/dynamic",
            Instant.parse("2026-09-24T00:00:00Z"));
    when(repository.findFirstBySessionIdAndStateIn(any(), any()))
        .thenReturn(Optional.of(allocation));

    assertThatThrownBy(() -> service.release("ses_test"))
        .isInstanceOfSatisfying(
            ProxyProviderAdapter.ProxyProviderException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ProxyProviderAdapter.ErrorCode.RELEASE_FAILED);
              assertThat(error.retryable()).isTrue();
            });
    assertThat(allocation.getState()).isEqualTo("ALLOCATED");
  }

  @Test
  void shouldReleasePersistedConfiguredHttpAllocationAfterCatalogRotation() {
    var allocation =
        new ProxyAllocationEntity(
            "pxy_retired",
            "tenant-test",
            "ses_test",
            "retired-provider",
            "http://127.0.0.1:8082",
            "retired-endpoint-1",
            "CONFIGURED_HTTP",
            null,
            null,
            "203.0.113.20",
            "vault://tenant-test/proxy/retired",
            Instant.parse("2026-09-24T00:00:00Z"));
    when(repository.findFirstBySessionIdAndStateIn(any(), any()))
        .thenReturn(Optional.of(allocation));

    service.release("ses_test");

    assertThat(allocation.getState()).isEqualTo("RELEASED");
    verify(repository).save(allocation);
  }

  @Test
  void shouldForbidDirectFallbackInProduction() {
    assertThatThrownBy(
            () ->
                new StaticProxyApplicationService(
                    repository,
                    bindingProfiles,
                    bindingAssignments,
                    sessionRepository,
                    idempotency,
                    audit,
                    "static-test",
                    "",
                    "",
                    true,
                    "production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be enabled");
  }

  @Test
  void shouldCreateTenantBindingWithoutReturningCredentialReference() {
    when(idempotency.claimProxyBindingCreate(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(3));
    when(bindingProfiles.findAllByTenantIdOrderByUpdatedAtDesc("tenant-test"))
        .thenReturn(java.util.List.of());
    when(bindingProfiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view =
        service.createBinding(
            "tenant-test",
            "admin-test",
            "idem-test",
            "req-test",
            new ProxyBindingRequest(
                "Primary exit",
                "Tenant managed exit",
                "static-test",
                "singapore",
                "203.0.113.10",
                "vault://tenant-test/proxy/primary",
                true,
                null));

    assertThat(view.bindingProfileId()).startsWith("pbind_");
    assertThat(view.credentialConfigured()).isTrue();
    assertThat(view.healthState()).isEqualTo("UNVERIFIED");
    verify(audit).append(any());
  }

  @Test
  void shouldRetainConfiguredCredentialReferenceWhenAnUpdateOmitsIt() {
    var profile =
        new ProxyBindingProfileEntity(
            "pbind_1234567890123456",
            "tenant-test",
            "Primary exit",
            null,
            "static-test",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            true,
            "admin-test",
            Instant.parse("2026-07-26T00:00:00Z"));
    when(bindingProfiles.findByBindingProfileIdAndTenantId("pbind_1234567890123456", "tenant-test"))
        .thenReturn(Optional.of(profile));
    when(idempotency.claimProxyBindingMutation(any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(5));
    when(bindingProfiles.findAllByTenantIdOrderByUpdatedAtDesc("tenant-test"))
        .thenReturn(java.util.List.of(profile));
    when(bindingProfiles.saveAndFlush(profile)).thenReturn(profile);

    var view =
        service.updateBinding(
            "tenant-test",
            "admin-test",
            "pbind_1234567890123456",
            "idem-update",
            "req-update",
            new ProxyBindingRequest(
                "Primary exit",
                "Disabled after Session snapshot",
                "static-test",
                "singapore",
                "203.0.113.10",
                null,
                false,
                0L));

    assertThat(view.enabled()).isFalse();
    assertThat(view.credentialConfigured()).isTrue();
    assertThat(profile.getCredentialRef()).isEqualTo("vault://tenant-test/proxy/primary");
  }

  @Test
  void shouldSnapshotBindingConfigurationForNewSession() {
    var profile =
        new ProxyBindingProfileEntity(
            "pbind_1234567890123456",
            "tenant-test",
            "Singapore exit",
            null,
            "static-test",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            true,
            "admin-test",
            Instant.parse("2026-07-26T00:00:00Z"));
    when(bindingProfiles.findForAssignment("pbind_1234567890123456", "tenant-test"))
        .thenReturn(Optional.of(profile));

    service.assignBindingProfile(session(), "pbind_1234567890123456", "singapore", "admin-test");

    var assignment = ArgumentCaptor.forClass(SessionProxyBindingAssignmentEntity.class);
    verify(bindingAssignments).save(assignment.capture());
    assertThat(assignment.getValue().getSessionId()).isEqualTo("ses_test");
    assertThat(assignment.getValue().getBindingProfileId()).isEqualTo("pbind_1234567890123456");
    assertThat(assignment.getValue().getExpectedExitIp()).isEqualTo("203.0.113.10");
    assertThat(assignment.getValue().getCredentialRef())
        .isEqualTo("vault://tenant-test/proxy/primary");
  }

  @Test
  void shouldRejectExplicitBindingWhenProviderCapacityIsAlreadyReserved() {
    var profile =
        new ProxyBindingProfileEntity(
            "pbind_1234567890123456",
            "tenant-test",
            "Singapore exit",
            null,
            "static-test",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            true,
            BigDecimal.ZERO,
            80,
            2,
            "admin-test",
            Instant.parse("2026-07-26T00:00:00Z"));
    when(bindingProfiles.findForAssignment("pbind_1234567890123456", "tenant-test"))
        .thenReturn(Optional.of(profile));
    when(bindingAssignments.countActiveProviderReservations(
            "tenant-test", "static-test", "vault://tenant-test/proxy/primary"))
        .thenReturn(2L);

    assertThatThrownBy(
            () ->
                service.assignBindingProfile(
                    session(), "pbind_1234567890123456", "singapore", "admin-test"))
        .isInstanceOf(StaticProxyApplicationService.ProxyBindingRejectedException.class)
        .hasMessage("PROVIDER_CAPACITY_EXHAUSTED");
  }

  @Test
  void shouldRejectBindingFromDifferentRegion() {
    var profile =
        new ProxyBindingProfileEntity(
            "pbind_1234567890123456",
            "tenant-test",
            "Singapore exit",
            null,
            "static-test",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            true,
            "admin-test",
            Instant.parse("2026-07-26T00:00:00Z"));
    when(bindingProfiles.findForAssignment("pbind_1234567890123456", "tenant-test"))
        .thenReturn(Optional.of(profile));

    assertThatThrownBy(
            () ->
                service.assignBindingProfile(
                    session(), "pbind_1234567890123456", "frankfurt", "admin-test"))
        .isInstanceOf(StaticProxyApplicationService.ProxyBindingRejectedException.class)
        .hasMessage("BINDING_REGION_MISMATCH");
  }

  @Test
  void shouldAllocateFromTheConfiguredProviderCatalogWithoutExposingSecretMaterial()
      throws Exception {
    var catalog = tempDir.resolve("proxy-providers.json");
    Files.writeString(
        catalog,
        """
        {
          "version": 1,
          "providers": [
            {
              "providerId": "provider-a",
              "endpoint": "http://127.0.0.1:8101",
              "expectedExitIp": "203.0.113.10",
              "credentialRef": "vault://tenant-test/proxy/a"
            },
            {
              "providerId": "provider-b",
              "endpoint": "http://127.0.0.1:8102",
              "expectedExitIp": "203.0.113.20",
              "credentialRef": "vault://tenant-test/proxy/b"
            }
          ]
        }
        """);
    Files.setPosixFilePermissions(
        catalog, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
    var catalogService =
        new StaticProxyApplicationService(
            repository,
            bindingProfiles,
            bindingAssignments,
            sessionRepository,
            idempotency,
            audit,
            "unused-fallback",
            "",
            "",
            "",
            catalog.toString(),
            false,
            "test",
            routeLearning);
    when(repository.findFirstBySessionIdAndStateIn(any(), any())).thenReturn(Optional.empty());
    when(bindingAssignments.findBySessionIdAndTenantId("ses_test", "tenant-test"))
        .thenReturn(
            Optional.of(
                new SessionProxyBindingAssignmentEntity(
                    "ses_test",
                    "tenant-test",
                    "pbind_provider_b",
                    4,
                    "provider-b",
                    "singapore",
                    "203.0.113.20",
                    "vault://tenant-test/proxy/b",
                    "admin-test",
                    Instant.parse("2026-07-26T00:00:00Z"))));

    catalogService.ensureBinding(session());

    var allocation = ArgumentCaptor.forClass(ProxyAllocationEntity.class);
    verify(repository).save(allocation.capture());
    assertThat(allocation.getValue().getProvider()).isEqualTo("provider-b");
    assertThat(allocation.getValue().getEndpoint()).isEqualTo("http://127.0.0.1:8102");
    assertThat(allocation.getValue().getExpectedExitIp()).isEqualTo("203.0.113.20");
    assertThat(allocation.getValue().getCredentialRef()).isEqualTo("vault://tenant-test/proxy/b");
  }

  @Test
  void shouldAllocateAndReleaseThroughTheRemoteProviderAdapterWithoutTrustingCatalogEndpointState()
      throws Exception {
    var released = new java.util.concurrent.atomic.AtomicBoolean();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer adapter-service-token");
          assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isNotBlank();
          if ("POST".equals(exchange.getRequestMethod())) {
            var response =
                """
                {"endpointId":"vendor-endpoint-51","providerId":"dynamic-provider",
                 "endpoint":"http://127.0.0.1:18551","expectedExitIp":"203.0.113.51",
                 "credentialRef":"vault://tenant-test/proxy/dynamic","protocol":"HTTP",
                 "productType":"DATACENTER"}
                """
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
          } else {
            released.set(true);
            exchange.sendResponseHeaders(204, -1);
          }
          exchange.close();
        });
    server.start();
    try {
      var token = tempDir.resolve("remote-adapter-token");
      Files.writeString(token, "adapter-service-token\n");
      Files.setPosixFilePermissions(
          token, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
      var catalog = tempDir.resolve("remote-proxy-provider.json");
      Files.writeString(
          catalog,
          """
          {"version":2,"providers":[{
            "providerId":"dynamic-provider",
            "endpoint":"http://127.0.0.1:18051",
            "expectedExitIp":"203.0.113.99",
            "credentialRef":"vault://tenant-test/proxy/dynamic",
            "adapterType":"REMOTE_HTTP_V1",
            "adapterBaseUrl":"http://127.0.0.1:%d",
            "adapterServiceTokenFile":"%s",
            "adapterAllowedEndpointHosts":["127.0.0.1"]
          }]}
          """
              .formatted(server.getAddress().getPort(), token));
      Files.setPosixFilePermissions(
          catalog, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
      var remoteService =
          new StaticProxyApplicationService(
              repository,
              bindingProfiles,
              bindingAssignments,
              sessionRepository,
              idempotency,
              audit,
              "unused-fallback",
              "",
              "",
              "",
              catalog.toString(),
              false,
              "test");
      when(repository.findFirstBySessionIdAndStateIn(any(), any())).thenReturn(Optional.empty());
      when(bindingAssignments.findBySessionIdAndTenantId("ses_test", "tenant-test"))
          .thenReturn(
              Optional.of(
                  new SessionProxyBindingAssignmentEntity(
                      "ses_test",
                      "tenant-test",
                      "pbind_dynamic",
                      7,
                      "dynamic-provider",
                      null,
                      "203.0.113.99",
                      "vault://tenant-test/proxy/dynamic",
                      "admin-test",
                      Instant.parse("2026-09-24T00:00:00Z"))));

      remoteService.ensureBinding(session());

      var saved = ArgumentCaptor.forClass(ProxyAllocationEntity.class);
      verify(repository).save(saved.capture());
      var allocation = saved.getValue();
      assertThat(allocation.getProviderAdapterType()).isEqualTo("REMOTE_HTTP_V1");
      assertThat(allocation.getProviderEndpointId()).isEqualTo("vendor-endpoint-51");
      assertThat(allocation.getEndpoint()).isEqualTo("http://127.0.0.1:18551");
      assertThat(allocation.getExpectedExitIp()).isEqualTo("203.0.113.51");

      when(repository.findFirstBySessionIdAndStateIn(any(), any()))
          .thenReturn(Optional.of(allocation));
      remoteService.release("ses_test");

      assertThat(released).isTrue();
      assertThat(allocation.getState()).isEqualTo("RELEASED");
      verify(repository, times(2)).save(allocation);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void shouldExposeConcreteSafeProviderRoutesInsteadOfOnlyTheCatalogPlaceholder() throws Exception {
    var catalog = tempDir.resolve("proxy-provider-overview.json");
    Files.writeString(
        catalog,
        """
        {
          "version": 1,
          "providers": [
            {
              "providerId": "provider-a",
              "endpoint": "http://127.0.0.1:8131",
              "expectedExitIp": "203.0.113.31",
              "credentialRef": "vault://tenant-test/proxy/a",
              "regions": ["singapore"],
              "costPerGibUsd": 0.1250,
              "reputationScore": 92,
              "maxConcurrentSessions": 300
            },
            {
              "providerId": "provider-b",
              "endpoint": "http://127.0.0.1:8132",
              "expectedExitIp": "203.0.113.32",
              "credentialRef": "vault://tenant-test/proxy/b",
              "regions": ["frankfurt"],
              "costPerGibUsd": 0.0750,
              "reputationScore": 84,
              "maxConcurrentSessions": 500
            }
          ]
        }
        """);
    Files.setPosixFilePermissions(
        catalog, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
    var catalogService =
        new StaticProxyApplicationService(
            repository,
            bindingProfiles,
            bindingAssignments,
            sessionRepository,
            idempotency,
            audit,
            "unused-fallback",
            "",
            "",
            "",
            catalog.toString(),
            false,
            "test");
    when(repository.findAllByTenantIdOrderByAllocatedAtDesc("tenant-test"))
        .thenReturn(java.util.List.of());

    var overview = catalogService.overview("tenant-test");

    assertThat(overview.provider().state()).isEqualTo("CATALOG_CONFIGURED");
    assertThat(overview.providers())
        .extracting("providerId")
        .containsExactly("provider-a", "provider-b");
    assertThat(overview.providers().getFirst().costPerGibUsd()).isEqualByComparingTo("0.1250");
    assertThat(overview.providers().getFirst().regions()).containsExactly("singapore");
  }

  @Test
  void shouldUseVerifiedBusinessOutcomesAndBoundedProfileStickinessWithoutBypassingGates()
      throws Exception {
    var catalog = tempDir.resolve("proxy-routing-providers.json");
    Files.writeString(
        catalog,
        """
        {
          "version": 1,
          "providers": [
            {
              "providerId": "provider-premium",
              "endpoint": "http://127.0.0.1:8111",
              "expectedExitIp": "203.0.113.11",
              "credentialRef": "vault://tenant-test/proxy/premium",
              "regions": ["singapore"],
              "costPerGibUsd": 2.0000,
              "reputationScore": 90,
              "maxConcurrentSessions": 10
            },
            {
              "providerId": "provider-efficient",
              "endpoint": "http://127.0.0.1:8112",
              "expectedExitIp": "203.0.113.12",
              "credentialRef": "vault://tenant-test/proxy/efficient",
              "costPerGibUsd": 0.1000,
              "reputationScore": 80,
              "maxConcurrentSessions": 100
            }
          ]
        }
        """);
    Files.setPosixFilePermissions(
        catalog, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
    var catalogService =
        new StaticProxyApplicationService(
            repository,
            bindingProfiles,
            bindingAssignments,
            sessionRepository,
            idempotency,
            audit,
            "unused-fallback",
            "",
            "",
            "",
            catalog.toString(),
            false,
            "test",
            routeLearning);
    when(routeLearning.evidence("tenant-test"))
        .thenReturn(
            java.util.Map.of(
                "pbind_premium000000001",
                new ProxyRouteLearningApplicationService.BusinessOutcomeEvidence(
                    "provider-premium",
                    10,
                    2,
                    8,
                    0.2,
                    20.0,
                    4,
                    Instant.parse("2026-07-25T23:59:00Z")),
                "pbind_efficient0000001",
                new ProxyRouteLearningApplicationService.BusinessOutcomeEvidence(
                    "provider-efficient",
                    10,
                    9,
                    1,
                    0.9,
                    90.0,
                    0,
                    Instant.parse("2026-07-25T23:59:00Z"))));
    when(routeLearning.previousBindingForProfile("tenant-test", "profile-test", "ses_test"))
        .thenReturn("pbind_premium000000001");
    var premium =
        routingProfile(
            "pbind_premium000000001",
            "provider-premium",
            "vault://tenant-test/proxy/premium",
            "203.0.113.11",
            "singapore",
            1.0,
            100.0,
            "2.0000",
            90,
            10);
    var efficient =
        routingProfile(
            "pbind_efficient0000001",
            "provider-efficient",
            "vault://tenant-test/proxy/efficient",
            "203.0.113.12",
            null,
            0.9,
            1000.0,
            "0.1000",
            80,
            100);
    when(bindingProfiles.findAllForAutomaticRouting("tenant-test"))
        .thenReturn(java.util.List.of(premium, efficient));
    when(bindingAssignments.countActiveProviderReservations(
            "tenant-test", "provider-premium", "vault://tenant-test/proxy/premium"))
        .thenReturn(1L);
    when(bindingAssignments.countActiveProviderReservations(
            "tenant-test", "provider-efficient", "vault://tenant-test/proxy/efficient"))
        .thenReturn(1L);

    catalogService.assignBindingProfile(session(), null, "singapore", "admin-test");

    var assignment = ArgumentCaptor.forClass(SessionProxyBindingAssignmentEntity.class);
    verify(bindingAssignments).save(assignment.capture());
    assertThat(assignment.getValue().getSelectionMode()).isEqualTo("AUTO");
    assertThat(assignment.getValue().getSelectionReason()).isEqualTo("PROFILE_STICKY");
    assertThat(assignment.getValue().getBindingProfileId()).isEqualTo("pbind_premium000000001");
    assertThat(assignment.getValue().getRoutingScore()).isGreaterThan(70.0);
    assertThat(assignment.getValue().getQualityScore()).isEqualTo(99);
    assertThat(assignment.getValue().getCostPerGibUsd()).isEqualByComparingTo("2.0000");
    assertThat(assignment.getValue().getCandidateScores())
        .hasSize(2)
        .extracting(candidate -> candidate.get("providerId"))
        .containsExactly("provider-efficient", "provider-premium");
    assertThat(assignment.getValue().getCandidateScores())
        .extracting(candidate -> candidate.get("businessOutcomeScore"))
        .containsExactly(90.0, 20.0);
    when(bindingAssignments.findBySessionIdAndTenantId("ses_test", "tenant-test"))
        .thenReturn(Optional.of(assignment.getValue()));
    var decision = catalogService.assignedRoutingDecision("ses_test", "tenant-test");
    assertThat(decision.selectionMode()).isEqualTo("AUTO");
    assertThat(decision.selectionReason()).isEqualTo("PROFILE_STICKY");
    assertThat(decision.candidateScores())
        .extracting("providerId")
        .containsExactly("provider-efficient", "provider-premium");
    verify(audit).append(any());
  }

  @Test
  void shouldFailClosedWhenNoFreshHealthyAutomaticProxyRouteExists() throws Exception {
    var catalog = tempDir.resolve("proxy-routing-empty.json");
    Files.writeString(
        catalog,
        """
        {"version":1,"providers":[
          {"providerId":"provider-a","endpoint":"http://127.0.0.1:8121","expectedExitIp":"203.0.113.21","credentialRef":"vault://tenant-test/proxy/a"},
          {"providerId":"provider-b","endpoint":"http://127.0.0.1:8122","expectedExitIp":"203.0.113.22","credentialRef":"vault://tenant-test/proxy/b"}
        ]}
        """);
    Files.setPosixFilePermissions(
        catalog, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
    var catalogService =
        new StaticProxyApplicationService(
            repository,
            bindingProfiles,
            bindingAssignments,
            sessionRepository,
            idempotency,
            audit,
            "unused-fallback",
            "",
            "",
            "",
            catalog.toString(),
            false,
            "test");
    when(bindingProfiles.findAllForAutomaticRouting("tenant-test")).thenReturn(java.util.List.of());

    assertThatThrownBy(
            () -> catalogService.assignBindingProfile(session(), null, "singapore", "admin-test"))
        .isInstanceOf(StaticProxyApplicationService.ProxyUnavailableException.class)
        .hasMessage("NO_HEALTHY_PROXY_ROUTE");
  }

  @Test
  void shouldExcludeAProviderQuarantinedForTheExplicitRoutingSite() throws Exception {
    var catalog = tempDir.resolve("proxy-routing-site-risk.json");
    Files.writeString(
        catalog,
        """
        {"version":1,"providers":[
          {"providerId":"provider-a","endpoint":"http://127.0.0.1:8121","expectedExitIp":"203.0.113.21","credentialRef":"vault://tenant-test/proxy/a"},
          {"providerId":"provider-b","endpoint":"http://127.0.0.1:8122","expectedExitIp":"203.0.113.22","credentialRef":"vault://tenant-test/proxy/b"}
        ]}
        """);
    Files.setPosixFilePermissions(
        catalog, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
    var catalogService =
        new StaticProxyApplicationService(
            repository,
            bindingProfiles,
            bindingAssignments,
            sessionRepository,
            idempotency,
            audit,
            "unused-fallback",
            "",
            "",
            "",
            catalog.toString(),
            false,
            "test",
            routeLearning);
    var blocked = mock(ProxyBindingProfileEntity.class);
    when(blocked.getBindingProfileId()).thenReturn("pbind_blocked000000001");
    when(blocked.getProviderId()).thenReturn("provider-a");
    when(blocked.isEnabled()).thenReturn(true);
    when(blocked.getHealthState()).thenReturn("HEALTHY");
    when(blocked.getLastHealthCheckedAt()).thenReturn(Instant.now());
    when(blocked.getRegion()).thenReturn(null);
    var admitted =
        routingProfile(
            "pbind_admitted00000001",
            "provider-b",
            "vault://tenant-test/proxy/b",
            "203.0.113.22",
            null,
            0.9,
            100.0,
            "0.2000",
            80,
            100);
    when(bindingProfiles.findAllForAutomaticRouting("tenant-test"))
        .thenReturn(java.util.List.of(blocked, admitted));
    when(routeLearning.evidence("tenant-test")).thenReturn(java.util.Map.of());
    when(routeLearning.siteChallengeEvidence(
            org.mockito.ArgumentMatchers.eq("tenant-test"),
            org.mockito.ArgumentMatchers.eq("example.com"),
            any()))
        .thenReturn(
            java.util.Map.of(
                "pbind_blocked000000001",
                new ProxyRouteLearningApplicationService.SiteChallengeEvidence(
                    "provider-a", 4, 3, true, Instant.now())));

    catalogService.assignBindingProfile(session(), null, "singapore", "admin-test", "Example.COM");

    var assignment = ArgumentCaptor.forClass(SessionProxyBindingAssignmentEntity.class);
    verify(bindingAssignments).save(assignment.capture());
    assertThat(assignment.getValue().getBindingProfileId()).isEqualTo("pbind_admitted00000001");
    assertThat(assignment.getValue().getRoutingSiteDomainHash())
        .isEqualTo(ProxyRouteLearningApplicationService.siteDomainHash("example.com"));
  }

  @Test
  void shouldUseAStableFivePercentExplorationBucket() {
    var selected =
        java.util.stream.IntStream.range(0, 200)
            .filter(
                index ->
                    StaticProxyApplicationService.shouldExplore(
                        "tenant-test", "ses_explore_" + index))
            .boxed()
            .toList();

    assertThat(selected).hasSizeBetween(8, 12);
    assertThat(selected)
        .allMatch(
            index ->
                StaticProxyApplicationService.shouldExplore("tenant-test", "ses_explore_" + index));
  }

  @Test
  void shouldNotCarryLearnedEvidenceAcrossAProviderChange() {
    var profile =
        new ProxyBindingProfileEntity(
            "pbind_1234567890123456",
            "tenant-test",
            "Changed provider",
            null,
            "provider-new",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/new",
            true,
            "admin-test",
            Instant.parse("2026-09-23T00:00:00Z"));
    var stale =
        new ProxyRouteLearningApplicationService.BusinessOutcomeEvidence(
            "provider-old", 100, 99, 1, 0.99, 99.0, 0, Instant.parse("2026-09-23T00:00:00Z"));

    var result =
        StaticProxyApplicationService.currentProviderEvidence(
            profile, java.util.Map.of(profile.getBindingProfileId(), stale));

    assertThat(result.sampleCount()).isZero();
    assertThat(result.score()).isEqualTo(50.0);
  }

  @Test
  void shouldNotCarrySiteQuarantineAcrossAProviderChange() {
    var profile =
        new ProxyBindingProfileEntity(
            "pbind_1234567890123456",
            "tenant-test",
            "Changed provider",
            null,
            "provider-new",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/new",
            true,
            "admin-test",
            Instant.parse("2026-09-23T00:00:00Z"));
    var stale =
        new ProxyRouteLearningApplicationService.SiteChallengeEvidence(
            "provider-old", 5, 3, true, Instant.parse("2026-09-23T00:00:00Z"));

    var result =
        StaticProxyApplicationService.currentSiteEvidence(
            profile, java.util.Map.of(profile.getBindingProfileId(), stale));

    assertThat(result.quarantined()).isFalse();
    assertThat(result.challengeCount()).isZero();
  }

  @Test
  void shouldCommitRebindOnlyAfterSourceAllocationWasReleasedAndSessionHibernated() {
    var now = Instant.parse("2026-07-26T00:00:00Z");
    var hibernated = session().withState(SessionState.HIBERNATED).withProxyBinding("pxy_source");
    var source =
        new ProxyAllocationEntity(
            "pxy_source", "tenant-test", "ses_test", "static-test", "http://127.0.0.1:8081", now);
    source.release(now);
    var target =
        new ProxyBindingProfileEntity(
            "pbind_target0000000001",
            "tenant-test",
            "Approved target",
            null,
            "static-test",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            true,
            "admin-test",
            now);
    when(sessionRepository.requireForUpdate("ses_test")).thenReturn(hibernated);
    when(repository.findById("pxy_source")).thenReturn(Optional.of(source));
    when(bindingProfiles.findForAssignment("pbind_target0000000001", "tenant-test"))
        .thenReturn(Optional.of(target));

    var rebound =
        service.commitRebindAfterHibernate(
            "ses_test",
            "tenant-test",
            "pbind_target0000000001",
            0,
            "admin-test",
            "req-test",
            "prb-test",
            "singapore");

    assertThat(rebound.proxyBindingId()).isNull();
    assertThat(rebound.contextEpoch()).isEqualTo(hibernated.contextEpoch() + 1);
    verify(bindingAssignments)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                assignment ->
                    assignment.getBindingProfileId().equals("pbind_target0000000001")
                        && assignment
                            .getCredentialRef()
                            .equals("vault://tenant-test/proxy/primary")));
    verify(sessionRepository).updateWithExpectedEpoch(rebound, hibernated.contextEpoch());
    verify(audit).append(any());
  }

  @Test
  void shouldRejectSameBindingRotationWhenTheProviderHasNoRotationCapability() {
    var now = Instant.parse("2026-09-24T00:00:00Z");
    var profile =
        new ProxyBindingProfileEntity(
            "pbind_static0000000001",
            "tenant-test",
            "Static route",
            null,
            "static-test",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            true,
            "admin-test",
            now);
    var assignment =
        new SessionProxyBindingAssignmentEntity(
            "ses_test",
            "tenant-test",
            "pbind_static0000000001",
            0,
            "static-test",
            "singapore",
            "203.0.113.10",
            "vault://tenant-test/proxy/primary",
            "admin-test",
            now);
    when(bindingProfiles.findByBindingProfileIdAndTenantId("pbind_static0000000001", "tenant-test"))
        .thenReturn(Optional.of(profile));
    when(bindingAssignments.findBySessionIdAndTenantId("ses_test", "tenant-test"))
        .thenReturn(Optional.of(assignment));

    assertThatThrownBy(
            () ->
                service.validateRebindTarget(
                    "ses_test", "tenant-test", "pbind_static0000000001", "singapore"))
        .isInstanceOf(StaticProxyApplicationService.ProxyBindingRejectedException.class)
        .hasMessage("PROVIDER_ROTATION_UNSUPPORTED");
  }

  @Test
  void shouldRotateTheSameDynamicBindingOnlyAfterTheSourceWasReleased() throws Exception {
    var rotationIdempotencyKey = new java.util.concurrent.atomic.AtomicReference<String>();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer adapter-service-token");
          var path = exchange.getRequestURI().getPath();
          byte[] response;
          if (path.endsWith("/capabilities")) {
            response =
                """
                {"protocols":["HTTP"],"productTypes":["DATACENTER"],
                 "stickySession":true,"countrySelection":false,"citySelection":false,
                 "asnSelection":false,"ipFamilies":["IPV4"],"rotation":true,
                 "bandwidthMetering":true,"providerWebhook":false}
                """
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          } else {
            assertThat(path).endsWith("/bindings/pbind_dynamic00000001/rotate");
            rotationIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            response =
                """
                {"previousEndpointId":"vendor-endpoint-old","endpoint":{
                  "endpointId":"vendor-endpoint-new","providerId":"dynamic-provider",
                  "endpoint":"http://127.0.0.1:18552","expectedExitIp":"203.0.113.52",
                  "credentialRef":"vault://tenant-test/proxy/dynamic","protocol":"HTTP",
                  "productType":"DATACENTER"}}
                """
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          }
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var token = tempDir.resolve("rotation-adapter-token");
      Files.writeString(token, "adapter-service-token\n");
      Files.setPosixFilePermissions(
          token, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
      var catalog = tempDir.resolve("rotation-provider.json");
      Files.writeString(
          catalog,
          """
          {"version":2,"providers":[{
            "providerId":"dynamic-provider",
            "endpoint":"http://127.0.0.1:18051",
            "expectedExitIp":"203.0.113.99",
            "credentialRef":"vault://tenant-test/proxy/dynamic",
            "adapterType":"REMOTE_HTTP_V1",
            "adapterBaseUrl":"http://127.0.0.1:%d",
            "adapterServiceTokenFile":"%s",
            "adapterAllowedEndpointHosts":["127.0.0.1"]
          }]}
          """
              .formatted(server.getAddress().getPort(), token));
      Files.setPosixFilePermissions(
          catalog, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
      var remoteService =
          new StaticProxyApplicationService(
              repository,
              bindingProfiles,
              bindingAssignments,
              sessionRepository,
              idempotency,
              audit,
              "unused-fallback",
              "",
              "",
              "",
              catalog.toString(),
              false,
              "test");
      var now = Instant.parse("2026-09-24T00:00:00Z");
      var hibernated = session().withState(SessionState.HIBERNATED).withProxyBinding("pxy_source");
      var source =
          new ProxyAllocationEntity(
              "pxy_source",
              "tenant-test",
              "ses_test",
              "dynamic-provider",
              "http://127.0.0.1:18551",
              "vendor-endpoint-old",
              "REMOTE_HTTP_V1",
              "pbind_dynamic00000001",
              0L,
              "203.0.113.51",
              "vault://tenant-test/proxy/dynamic",
              now);
      source.release(now);
      var profile =
          new ProxyBindingProfileEntity(
              "pbind_dynamic00000001",
              "tenant-test",
              "Dynamic route",
              null,
              "dynamic-provider",
              null,
              "203.0.113.99",
              "vault://tenant-test/proxy/dynamic",
              true,
              "admin-test",
              now);
      var assignment =
          new SessionProxyBindingAssignmentEntity(
              "ses_test",
              "tenant-test",
              "pbind_dynamic00000001",
              0,
              "dynamic-provider",
              null,
              "203.0.113.99",
              "vault://tenant-test/proxy/dynamic",
              "admin-test",
              now);
      when(sessionRepository.requireForUpdate("ses_test")).thenReturn(hibernated);
      when(repository.findById("pxy_source")).thenReturn(Optional.of(source));
      when(bindingProfiles.findForAssignment("pbind_dynamic00000001", "tenant-test"))
          .thenReturn(Optional.of(profile));
      when(bindingAssignments.findBySessionIdAndTenantId("ses_test", "tenant-test"))
          .thenReturn(Optional.of(assignment));

      var rebound =
          remoteService.commitRebindAfterHibernate(
              "ses_test",
              "tenant-test",
              "pbind_dynamic00000001",
              0,
              "admin-test",
              "req-rotate",
              "prb_rotationworkflow0000000000001",
              "singapore");

      var saved = ArgumentCaptor.forClass(ProxyAllocationEntity.class);
      verify(repository).save(saved.capture());
      assertThat(rebound.proxyBindingId()).isEqualTo(saved.getValue().getAllocationId());
      assertThat(saved.getValue().getProviderEndpointId()).isEqualTo("vendor-endpoint-new");
      assertThat(saved.getValue().getExpectedExitIp()).isEqualTo("203.0.113.52");
      assertThat(saved.getValue().getProviderAdapterType()).isEqualTo("REMOTE_HTTP_V1");
      assertThat(rotationIdempotencyKey.get()).isEqualTo("prb_rotationworkflow0000000000001");
      verify(sessionRepository).updateWithExpectedEpoch(rebound, hibernated.contextEpoch());
    } finally {
      server.stop(0);
    }
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

  private static ProxyBindingProfileEntity routingProfile(
      String id,
      String providerId,
      String credentialRef,
      String exitIp,
      String region,
      double successEwma,
      double latencyEwma,
      String cost,
      int reputation,
      int capacity) {
    var profile = mock(ProxyBindingProfileEntity.class);
    org.mockito.Mockito.lenient().when(profile.getBindingProfileId()).thenReturn(id);
    when(profile.getProviderId()).thenReturn(providerId);
    when(profile.getCredentialRef()).thenReturn(credentialRef);
    when(profile.getExpectedExitIp()).thenReturn(exitIp);
    when(profile.getRegion()).thenReturn(region);
    when(profile.isEnabled()).thenReturn(true);
    when(profile.getHealthState()).thenReturn("HEALTHY");
    when(profile.getLastHealthCheckedAt()).thenReturn(Instant.now());
    when(profile.getProbeSuccessEwma()).thenReturn(successEwma);
    when(profile.getProbeLatencyEwmaMs()).thenReturn(latencyEwma);
    when(profile.getCostPerGibUsd()).thenReturn(new BigDecimal(cost));
    when(profile.getReputationScore()).thenReturn(reputation);
    when(profile.getMaxConcurrentSessions()).thenReturn(capacity);
    org.mockito.Mockito.lenient().when(profile.getVersion()).thenReturn(3L);
    return profile;
  }
}
