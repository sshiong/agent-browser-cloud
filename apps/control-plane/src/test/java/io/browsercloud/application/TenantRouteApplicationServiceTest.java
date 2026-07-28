package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.CoordinatorRouteModels.RequestTenantRouteMigrationRequest;
import io.browsercloud.coordinator.CoordinatorOwnershipJpaRepository;
import io.browsercloud.coordinator.CoordinatorShardRouter;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.CoordinatorRouteMigrationEntity;
import io.browsercloud.persistence.CoordinatorRouteMigrationJpaRepository;
import io.browsercloud.persistence.CoordinatorSessionRouteEntity;
import io.browsercloud.persistence.CoordinatorSessionRouteJpaRepository;
import io.browsercloud.persistence.CoordinatorTenantRouteEntity;
import io.browsercloud.persistence.CoordinatorTenantRouteJpaRepository;
import io.browsercloud.persistence.SessionJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantRouteApplicationServiceTest {

  @Mock private CoordinatorTenantRouteJpaRepository tenantRoutes;
  @Mock private CoordinatorSessionRouteJpaRepository sessionRoutes;
  @Mock private CoordinatorRouteMigrationJpaRepository migrations;
  @Mock private SessionJpaRepository sessionJpa;
  @Mock private SessionRepository sessions;
  @Mock private CoordinatorOwnershipJpaRepository ownership;
  @Mock private SafePointApplicationService safePoints;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;

  private TenantRouteApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new TenantRouteApplicationService(
            tenantRoutes,
            sessionRoutes,
            migrations,
            sessionJpa,
            sessions,
            new CoordinatorShardRouter(16),
            ownership,
            safePoints,
            idempotency,
            audit);
  }

  @Test
  void createsDurableMigrationWithMonotonicTargetEpoch() {
    var route = new CoordinatorTenantRouteEntity("tenant-test", Instant.now());
    when(tenantRoutes.findForUpdate("tenant-test")).thenReturn(Optional.of(route));
    when(idempotency.claimTenantRouteMigration(eq("tenant-test"), eq("route-idem-1"), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(3));

    var result =
        service.requestMigration(
            "tenant-test",
            "admin-test",
            "route-idem-1",
            "request-test",
            new RequestTenantRouteMigrationRequest(1, 8));

    assertThat(result.state()).isEqualTo("MIGRATING");
    assertThat(result.sourceRouteEpoch()).isEqualTo(1);
    assertThat(result.targetRouteEpoch()).isEqualTo(2);
    assertThat(route.getState()).isEqualTo("MIGRATING");
    assertThat(route.getPendingVirtualPartitions()).isEqualTo(8);
  }

  @Test
  void rejectsMigrationRequestedAgainstStaleEpoch() {
    var route = new CoordinatorTenantRouteEntity("tenant-test", Instant.now());
    when(tenantRoutes.findForUpdate("tenant-test")).thenReturn(Optional.of(route));
    when(idempotency.claimTenantRouteMigration(eq("tenant-test"), eq("route-idem-2"), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(3));

    assertThatThrownBy(
            () ->
                service.requestMigration(
                    "tenant-test",
                    "admin-test",
                    "route-idem-2",
                    "request-test",
                    new RequestTenantRouteMigrationRequest(9, 8)))
        .isInstanceOf(TenantRouteApplicationService.TenantRouteRejectedException.class)
        .hasMessage("STALE_ROUTE_EPOCH");
  }

  @Test
  void bindsNewSessionDirectlyToPendingEpochDuringMigration() {
    var now = Instant.now();
    var route = new CoordinatorTenantRouteEntity("tenant-test", now);
    route.beginMigration("crm_test", 4, 2, now);
    var expected = new CoordinatorShardRouter(16).route("tenant-test", "ses_test", 4, 2);
    var persisted =
        new CoordinatorSessionRouteEntity(
            "ses_test",
            "tenant-test",
            expected.routeEpoch(),
            expected.virtualPartition(),
            expected.shardId(),
            now);
    when(tenantRoutes.findById("tenant-test")).thenReturn(Optional.of(route));
    when(sessionRoutes.findById("ses_test"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(persisted));

    var result = service.bindNewSession("ses_test", "tenant-test");

    assertThat(result.routeEpoch()).isEqualTo(2);
    assertThat(result.virtualPartition()).isBetween(0, 3);
    verify(sessionRoutes)
        .bindIfAbsent(
            eq("ses_test"),
            eq("tenant-test"),
            eq(2L),
            eq(expected.virtualPartition()),
            eq(expected.shardId()),
            any());
  }

  @Test
  void keepsUnsafeSessionOnSourceEpoch() {
    var now = Instant.now();
    var route = new CoordinatorTenantRouteEntity("tenant-test", now);
    route.beginMigration("crm_test", 4, 2, now);
    var migration =
        new CoordinatorRouteMigrationEntity(
            "crm_test", "tenant-test", 1, 2, 1, 4, "admin-test", "request-test", now);
    var sessionRoute = new CoordinatorSessionRouteEntity("ses_test", "tenant-test", 1, 0, 0, now);
    when(migrations.findForUpdate("crm_test")).thenReturn(Optional.of(migration));
    when(tenantRoutes.findForUpdate("tenant-test")).thenReturn(Optional.of(route));
    when(sessionJpa.findIdsMissingCoordinatorRoute(eq("tenant-test"), any())).thenReturn(List.of());
    when(sessionRoutes.findPending(eq("tenant-test"), eq(2L), any()))
        .thenReturn(List.of(sessionRoute));
    when(sessions.requireForUpdate("ses_test")).thenReturn(session());
    when(safePoints.assess("ses_test", "tenant-test"))
        .thenReturn(
            new io.browsercloud.api.SafePointModels.SessionSafePointView(
                "ses_test", false, "WAITING", "FRESH", null, 0, now, null, List.of()));
    when(sessionJpa.countByTenantId("tenant-test")).thenReturn(1L);
    when(sessionRoutes.countByTenantIdAndRouteEpoch("tenant-test", 2)).thenReturn(0L);

    service.reconcile("crm_test");

    assertThat(migration.getBlockedSessions()).isEqualTo(1);
    assertThat(route.getState()).isEqualTo("MIGRATING");
    assertThat(sessionRoute.getRouteEpoch()).isEqualTo(1);
    verify(ownership, never()).fenceRouteEpoch(any(), anyLong(), any());
  }

  @Test
  void migratesSafeSessionAndCommitsTenantRoute() {
    var now = Instant.now();
    var route = new CoordinatorTenantRouteEntity("tenant-test", now);
    route.beginMigration("crm_test", 4, 2, now);
    var migration =
        new CoordinatorRouteMigrationEntity(
            "crm_test", "tenant-test", 1, 2, 1, 4, "admin-test", "request-test", now);
    var sessionRoute = new CoordinatorSessionRouteEntity("ses_test", "tenant-test", 1, 0, 0, now);
    when(migrations.findForUpdate("crm_test")).thenReturn(Optional.of(migration));
    when(tenantRoutes.findForUpdate("tenant-test")).thenReturn(Optional.of(route));
    when(sessionJpa.findIdsMissingCoordinatorRoute(eq("tenant-test"), any())).thenReturn(List.of());
    when(sessionRoutes.findPending(eq("tenant-test"), eq(2L), any()))
        .thenReturn(List.of(sessionRoute));
    when(sessions.requireForUpdate("ses_test")).thenReturn(session());
    when(safePoints.assess("ses_test", "tenant-test"))
        .thenReturn(
            new io.browsercloud.api.SafePointModels.SessionSafePointView(
                "ses_test", true, "SAFE", "FRESH", null, 0, now, null, List.of()));
    when(sessionRoutes.findForUpdate("ses_test")).thenReturn(Optional.of(sessionRoute));
    when(sessionJpa.countByTenantId("tenant-test")).thenReturn(1L);
    when(sessionRoutes.countByTenantIdAndRouteEpoch("tenant-test", 2)).thenReturn(1L);
    when(sessionJpa.countMissingCoordinatorRoute("tenant-test")).thenReturn(0L);

    service.reconcile("crm_test");

    assertThat(migration.getState()).isEqualTo("COMMITTED");
    assertThat(route.getState()).isEqualTo("STABLE");
    assertThat(route.getActiveRouteEpoch()).isEqualTo(2);
    assertThat(sessionRoute.getRouteEpoch()).isEqualTo(2);
    verify(ownership).fenceRouteEpoch(eq("ses_test"), eq(2L), any());
  }

  private SessionContext session() {
    var now = Instant.now();
    return new SessionContext(
        "ses_test",
        "tenant-test",
        "profile-test",
        null,
        "runtime-test",
        null,
        null,
        0,
        0,
        0,
        0,
        ResourceClass.L2,
        SessionState.RUNNING,
        "",
        now,
        now);
  }
}
