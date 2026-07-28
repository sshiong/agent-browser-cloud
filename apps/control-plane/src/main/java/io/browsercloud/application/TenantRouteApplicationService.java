package io.browsercloud.application;

import static io.browsercloud.api.CoordinatorRouteModels.*;

import io.browsercloud.coordinator.CoordinatorOwnershipJpaRepository;
import io.browsercloud.coordinator.CoordinatorRouteAuthority;
import io.browsercloud.coordinator.CoordinatorShardRouter;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.CoordinatorRouteMigrationEntity;
import io.browsercloud.persistence.CoordinatorRouteMigrationJpaRepository;
import io.browsercloud.persistence.CoordinatorSessionRouteEntity;
import io.browsercloud.persistence.CoordinatorSessionRouteJpaRepository;
import io.browsercloud.persistence.CoordinatorTenantRouteEntity;
import io.browsercloud.persistence.CoordinatorTenantRouteJpaRepository;
import io.browsercloud.persistence.SessionJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable tenant route authority.
 *
 * <p>A migration is incremental: new Sessions bind directly to the pending epoch while existing
 * Sessions move only while their durable safe point is SAFE. The Session row lock serializes route
 * movement with all lifecycle and safety-lease mutations.
 */
@Service
public class TenantRouteApplicationService implements CoordinatorRouteAuthority {

  private static final int RECONCILE_BATCH = 100;

  private final CoordinatorTenantRouteJpaRepository tenantRoutes;
  private final CoordinatorSessionRouteJpaRepository sessionRoutes;
  private final CoordinatorRouteMigrationJpaRepository migrations;
  private final SessionJpaRepository sessionJpa;
  private final SessionRepository sessions;
  private final CoordinatorShardRouter router;
  private final CoordinatorOwnershipJpaRepository ownership;
  private final SafePointApplicationService safePoints;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;

  public TenantRouteApplicationService(
      CoordinatorTenantRouteJpaRepository tenantRoutes,
      CoordinatorSessionRouteJpaRepository sessionRoutes,
      CoordinatorRouteMigrationJpaRepository migrations,
      SessionJpaRepository sessionJpa,
      SessionRepository sessions,
      CoordinatorShardRouter router,
      CoordinatorOwnershipJpaRepository ownership,
      SafePointApplicationService safePoints,
      IdempotencyService idempotency,
      AuditApplicationService audit) {
    this.tenantRoutes = tenantRoutes;
    this.sessionRoutes = sessionRoutes;
    this.migrations = migrations;
    this.sessionJpa = sessionJpa;
    this.sessions = sessions;
    this.router = router;
    this.ownership = ownership;
    this.safePoints = safePoints;
    this.idempotency = idempotency;
    this.audit = audit;
  }

  @Override
  @Transactional
  public SessionRoute resolve(String sessionId) {
    var session = sessions.require(sessionId);
    return bindIfAbsent(session.sessionId(), session.tenantId());
  }

  @Transactional
  public SessionRoute bindNewSession(String sessionId, String tenantId) {
    return bindIfAbsent(sessionId, tenantId);
  }

  @Transactional
  public TenantRouteView get(String tenantId) {
    return toView(requireTenantRoute(tenantId));
  }

  @Transactional(readOnly = true)
  public Optional<TenantRouteMigrationView> latestMigration(String tenantId) {
    return migrations.findFirstByTenantIdOrderByCreatedAtDesc(tenantId).map(this::toView);
  }

  @Transactional
  public TenantRouteMigrationView requestMigration(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      RequestTenantRouteMigrationRequest request) {
    var candidateId = newId("crm_");
    var claimedId =
        idempotency.claimTenantRouteMigration(tenantId, idempotencyKey, request, candidateId);
    if (!claimedId.equals(candidateId)) {
      return migrations
          .findById(claimedId)
          .map(this::toView)
          .orElseThrow(() -> new TenantRouteRejectedException("IDEMPOTENT_MIGRATION_NOT_FOUND"));
    }

    var route = requireTenantRouteForUpdate(tenantId);
    if (!"STABLE".equals(route.getState())) {
      throw new TenantRouteRejectedException("TENANT_ROUTE_MIGRATION_ACTIVE");
    }
    if (route.getActiveRouteEpoch() != request.expectedRouteEpoch()) {
      throw new TenantRouteRejectedException("STALE_ROUTE_EPOCH");
    }
    if (route.getActiveVirtualPartitions() == request.targetVirtualPartitions()) {
      throw new TenantRouteRejectedException("VIRTUAL_PARTITIONS_UNCHANGED");
    }

    var now = Instant.now();
    var targetEpoch = Math.addExact(route.getActiveRouteEpoch(), 1);
    var migration =
        new CoordinatorRouteMigrationEntity(
            claimedId,
            tenantId,
            route.getActiveRouteEpoch(),
            targetEpoch,
            route.getActiveVirtualPartitions(),
            request.targetVirtualPartitions(),
            actorId,
            requestId,
            now);
    migrations.save(migration);
    route.beginMigration(claimedId, request.targetVirtualPartitions(), targetEpoch, now);
    tenantRoutes.save(route);
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "COORDINATOR_ROUTE",
            "USER",
            actorId,
            "TENANT_ROUTE_MIGRATION",
            claimedId,
            "REQUEST",
            "ACCEPTED",
            Map.of(
                "sourceRouteEpoch", route.getActiveRouteEpoch(),
                "targetRouteEpoch", targetEpoch,
                "sourceVirtualPartitions", route.getActiveVirtualPartitions(),
                "targetVirtualPartitions", request.targetVirtualPartitions()),
            requestId));
    return toView(migration);
  }

  @Transactional
  public void reconcile(String migrationId) {
    var migration = migrations.findForUpdate(migrationId).orElseThrow();
    if (!"MIGRATING".equals(migration.getState())) return;
    var tenantRoute = tenantRoutes.findForUpdate(migration.getTenantId()).orElseThrow();
    if (!migrationId.equals(tenantRoute.getActiveMigrationId())
        || tenantRoute.getPendingRouteEpoch() == null
        || tenantRoute.getPendingVirtualPartitions() == null) {
      throw new TenantRouteRejectedException("TENANT_ROUTE_MIGRATION_INVARIANT");
    }

    bindMissingSessions(tenantRoute);
    int blocked = 0;
    var pending =
        sessionRoutes.findPending(
            migration.getTenantId(),
            migration.getTargetRouteEpoch(),
            PageRequest.of(0, RECONCILE_BATCH));
    for (var candidate : pending) {
      sessions.requireForUpdate(candidate.getSessionId());
      if (!safePoints.assess(candidate.getSessionId(), migration.getTenantId()).safe()) {
        blocked++;
        continue;
      }
      var locked = sessionRoutes.findForUpdate(candidate.getSessionId()).orElseThrow();
      if (locked.getRouteEpoch() >= migration.getTargetRouteEpoch()) continue;
      var calculated =
          router.route(
              migration.getTenantId(),
              locked.getSessionId(),
              migration.getTargetVirtualPartitions(),
              migration.getTargetRouteEpoch());
      locked.migrate(
          calculated.routeEpoch(),
          calculated.virtualPartition(),
          calculated.shardId(),
          Instant.now());
      sessionRoutes.save(locked);
      ownership.fenceRouteEpoch(
          locked.getSessionId(), calculated.routeEpoch(), Instant.now().minusSeconds(3600));
    }

    var total = Math.toIntExact(sessionJpa.countByTenantId(migration.getTenantId()));
    var migrated =
        Math.toIntExact(
            sessionRoutes.countByTenantIdAndRouteEpoch(
                migration.getTenantId(), migration.getTargetRouteEpoch()));
    var now = Instant.now();
    migration.progress(total, migrated, blocked, now);
    if (migrated == total
        && sessionJpa.countMissingCoordinatorRoute(migration.getTenantId()) == 0) {
      tenantRoute.commitMigration(now);
      migration.committed(now);
      audit.append(
          new AuditApplicationService.AuditRecord(
              migration.getTenantId(),
              null,
              "COORDINATOR_ROUTE",
              "SYSTEM",
              "system:coordinator-route",
              "TENANT_ROUTE_MIGRATION",
              migrationId,
              "COMMIT",
              "COMMITTED",
              Map.of(
                  "routeEpoch", migration.getTargetRouteEpoch(),
                  "virtualPartitions", migration.getTargetVirtualPartitions(),
                  "migratedSessions", migrated),
              migration.getRequestId()));
    }
    migrations.save(migration);
    tenantRoutes.save(tenantRoute);
  }

  private void bindMissingSessions(CoordinatorTenantRouteEntity tenantRoute) {
    sessionJpa
        .findIdsMissingCoordinatorRoute(
            tenantRoute.getTenantId(), PageRequest.of(0, RECONCILE_BATCH))
        .forEach(sessionId -> bindIfAbsent(sessionId, tenantRoute.getTenantId()));
  }

  private SessionRoute bindIfAbsent(String sessionId, String tenantId) {
    var existing = sessionRoutes.findById(sessionId);
    if (existing.isPresent()) {
      return toRoute(existing.orElseThrow());
    }
    var tenantRoute = requireTenantRoute(tenantId);
    Integer partitions =
        "MIGRATING".equals(tenantRoute.getState())
            ? tenantRoute.getPendingVirtualPartitions()
            : Integer.valueOf(tenantRoute.getActiveVirtualPartitions());
    Long epoch =
        "MIGRATING".equals(tenantRoute.getState())
            ? tenantRoute.getPendingRouteEpoch()
            : Long.valueOf(tenantRoute.getActiveRouteEpoch());
    if (partitions == null || epoch == null) {
      throw new TenantRouteRejectedException("TENANT_ROUTE_PENDING_STATE_INVALID");
    }
    var calculated = router.route(tenantId, sessionId, partitions, epoch);
    sessionRoutes.bindIfAbsent(
        sessionId,
        tenantId,
        calculated.routeEpoch(),
        calculated.virtualPartition(),
        calculated.shardId(),
        Instant.now());
    return toRoute(sessionRoutes.findById(sessionId).orElseThrow());
  }

  private CoordinatorTenantRouteEntity requireTenantRoute(String tenantId) {
    tenantRoutes.ensure(tenantId, Instant.now());
    return tenantRoutes.findById(tenantId).orElseThrow();
  }

  private CoordinatorTenantRouteEntity requireTenantRouteForUpdate(String tenantId) {
    tenantRoutes.ensure(tenantId, Instant.now());
    return tenantRoutes.findForUpdate(tenantId).orElseThrow();
  }

  private SessionRoute toRoute(CoordinatorSessionRouteEntity route) {
    return new SessionRoute(
        route.getSessionId(),
        route.getTenantId(),
        route.getRouteEpoch(),
        route.getVirtualPartition(),
        route.getShardId());
  }

  private TenantRouteView toView(CoordinatorTenantRouteEntity route) {
    return new TenantRouteView(
        route.getTenantId(),
        route.getState(),
        route.getActiveVirtualPartitions(),
        route.getActiveRouteEpoch(),
        route.getPendingVirtualPartitions(),
        route.getPendingRouteEpoch(),
        route.getActiveMigrationId(),
        route.getVersion(),
        route.getUpdatedAt());
  }

  private TenantRouteMigrationView toView(CoordinatorRouteMigrationEntity migration) {
    return new TenantRouteMigrationView(
        migration.getMigrationId(),
        migration.getTenantId(),
        migration.getSourceRouteEpoch(),
        migration.getTargetRouteEpoch(),
        migration.getSourceVirtualPartitions(),
        migration.getTargetVirtualPartitions(),
        migration.getState(),
        migration.getTotalSessions(),
        migration.getMigratedSessions(),
        migration.getBlockedSessions(),
        migration.getRequestedBy(),
        migration.getRequestId(),
        migration.getFailureCode(),
        migration.getCreatedAt(),
        migration.getUpdatedAt(),
        migration.getCompletedAt());
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class TenantRouteRejectedException extends RuntimeException {
    public TenantRouteRejectedException(String reason) {
      super(reason);
    }
  }
}
