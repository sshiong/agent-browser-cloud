package io.browsercloud.application;

import io.browsercloud.api.OperationResponse;
import io.browsercloud.api.SessionMigrationView;
import io.browsercloud.api.StateResyncRequest;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionMigrationEntity;
import io.browsercloud.persistence.SessionMigrationJpaRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable two-operation cross-node migration with checkpoint restore and recovery validation. */
@Service
public class SessionMigrationApplicationService {

  private static final Set<String> ACTIVE_PHASES =
      Set.of(
          "CHECKPOINTING",
          "PLACING_TARGET",
          "RESTORING",
          "STATE_RESYNC",
          "BUSINESS_VALIDATION",
          "BUSINESS_RECOVERY_ACTION");

  private final SessionMigrationJpaRepository migrations;
  private final SessionRepository sessions;
  private final BrowserCapacityApplicationService capacity;
  private final SessionApplicationService sessionService;
  private final ProfileApplicationService profiles;
  private final SafePointApplicationService safePoints;
  private final StateGatewayApplicationService stateGateway;
  private final BrowserStateRepository browserStates;
  private final ApplicationBusinessRecoveryService recoveryService;
  private final BusinessRecoveryActionApplicationService recoveryActions;
  private final SessionResourceApplicationService resources;

  public SessionMigrationApplicationService(
      SessionMigrationJpaRepository migrations,
      SessionRepository sessions,
      BrowserCapacityApplicationService capacity,
      SessionApplicationService sessionService,
      ProfileApplicationService profiles,
      SafePointApplicationService safePoints,
      StateGatewayApplicationService stateGateway,
      BrowserStateRepository browserStates,
      ApplicationBusinessRecoveryService recoveryService,
      BusinessRecoveryActionApplicationService recoveryActions,
      SessionResourceApplicationService resources) {
    this.migrations = migrations;
    this.sessions = sessions;
    this.capacity = capacity;
    this.sessionService = sessionService;
    this.profiles = profiles;
    this.safePoints = safePoints;
    this.stateGateway = stateGateway;
    this.browserStates = browserStates;
    this.recoveryService = recoveryService;
    this.recoveryActions = recoveryActions;
    this.resources = resources;
  }

  @Transactional
  public String request(String sessionId, String tenantId) {
    var session = requireTenantForUpdate(sessionId, tenantId);
    var existing =
        migrations.findFirstBySessionIdAndPhaseInOrderByCreatedAtDesc(sessionId, ACTIVE_PHASES);
    if (existing.isPresent()) {
      return existing.orElseThrow().getMigrationId();
    }
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new MigrationRejectedException("MIGRATION_REQUIRES_RUNNING_SESSION");
    }
    var safePoint = safePoints.assess(sessionId, tenantId);
    if (!safePoint.safe()) {
      throw new MigrationRejectedException("SAFE_POINT_NOT_REACHED");
    }
    var placement = capacity.getPlacement(sessionId, tenantId);
    var now = Instant.now();
    var migration =
        migrations.save(
            new SessionMigrationEntity(
                "mig_" + UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                tenantId,
                placement.nodeId(),
                session.contextEpoch(),
                now));
    var operation = sessionService.hibernateForResourcePolicy(sessionId, tenantId);
    migration.hibernateDispatched(operation.operationId(), now);
    migrations.save(migration);
    resources.recordMigrationPhase(
        sessionId,
        migration.getMigrationId(),
        "CHECKPOINTING",
        "SOURCE_CHECKPOINT_OPERATION_DISPATCHED",
        false,
        false);
    return migration.getMigrationId();
  }

  /**
   * Rechecks the Safe Point while holding the same Session row lock used by application lease
   * acquisition. This closes the race between a SAFE assessment and dispatching the hibernate
   * Operation.
   */
  @Transactional
  public OperationResponse hibernateAtSafePoint(String sessionId, String tenantId) {
    var session = requireTenantForUpdate(sessionId, tenantId);
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new MigrationRejectedException("HIBERNATE_REQUIRES_RUNNING_SESSION");
    }
    if (!safePoints.assess(sessionId, tenantId).safe()) {
      throw new MigrationRejectedException("SAFE_POINT_NOT_REACHED");
    }
    return sessionService.hibernateForResourcePolicy(sessionId, tenantId);
  }

  @Transactional(readOnly = true)
  public java.util.Optional<SessionMigrationView> latest(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return migrations.findFirstBySessionIdOrderByCreatedAtDesc(sessionId).map(this::toView);
  }

  @Transactional
  public void reconcile(String migrationId) {
    var migration = migrations.findById(migrationId).orElseThrow();
    switch (migration.getPhase()) {
      case "CHECKPOINTING" -> placeAndRestore(migration);
      case "RESTORING" -> requestStateResync(migration);
      case "STATE_RESYNC" -> observeResync(migration);
      case "BUSINESS_VALIDATION" -> validateBusinessRecovery(migration);
      case "BUSINESS_RECOVERY_ACTION" -> recoveryActions.reconcile(migration);
      default -> {}
    }
  }

  @Transactional
  public void fail(String migrationId, String reason) {
    migrations
        .findById(migrationId)
        .ifPresent(
            migration -> {
              var bounded =
                  reason == null || reason.isBlank()
                      ? "MIGRATION_RECONCILIATION_FAILED"
                      : reason.substring(0, Math.min(reason.length(), 240));
              migration.fail(bounded, Instant.now());
              migrations.save(migration);
              resources.recordMigrationPhase(
                  migration.getSessionId(), migrationId, "FAILED", bounded, true, false);
            });
  }

  private void placeAndRestore(SessionMigrationEntity migration) {
    var session = requireTenant(migration.getSessionId(), migration.getTenantId());
    if (session.state() == SessionState.HIBERNATING) return;
    if (session.state() != SessionState.HIBERNATED) {
      throw new MigrationRejectedException("SOURCE_DID_NOT_HIBERNATE");
    }
    var profile = profiles.get(migration.getTenantId(), session.profileId());
    if (profile.latestCheckpointId() == null || profile.latestCheckpointId().isBlank()) {
      throw new MigrationRejectedException("SOURCE_CHECKPOINT_MISSING");
    }
    var descriptor = sessions.describe(session.sessionId());
    var placement =
        capacity.reserveExcluding(session, descriptor.region(), migration.getSourceNodeId());
    var placedSession = sessions.require(session.sessionId());
    var operation =
        sessionService.start(migration.getSessionId(), migration.getTenantId(), "system:migration");
    migration.targetPlaced(
        placement.nodeId(),
        placedSession.contextEpoch(),
        profile.latestCheckpointId(),
        Instant.now());
    migration.restoreDispatched(operation.operationId(), Instant.now());
    migrations.save(migration);
    resources.recordMigrationPhase(
        migration.getSessionId(),
        migration.getMigrationId(),
        "RESTORING",
        "TARGET_NODE:" + placement.nodeId(),
        false,
        false);
  }

  private void requestStateResync(SessionMigrationEntity migration) {
    var session = requireTenant(migration.getSessionId(), migration.getTenantId());
    if (session.state() == SessionState.STARTING) return;
    if (session.state() != SessionState.RUNNING
        || !session.nodeId().equals(migration.getTargetNodeId())) {
      throw new MigrationRejectedException("TARGET_RUNTIME_RESTORE_FAILED");
    }
    var response =
        stateGateway.requestResync(
            migration.getSessionId(),
            migration.getTenantId(),
            new StateResyncRequest(
                StateResyncRequest.Mode.FULL, null, "MIGRATION_BUSINESS_RECOVERY"),
            "migration-" + migration.getMigrationId() + "-resync");
    migration.stateResync(response.requestId(), Instant.now());
    migrations.save(migration);
    resources.recordMigrationPhase(
        migration.getSessionId(),
        migration.getMigrationId(),
        "STATE_RESYNC",
        response.requestId(),
        false,
        false);
  }

  private void observeResync(SessionMigrationEntity migration) {
    var session = requireTenant(migration.getSessionId(), migration.getTenantId());
    var snapshot = browserStates.find(migration.getSessionId());
    if (snapshot.isEmpty()
        || snapshot.orElseThrow().contextEpoch() != session.contextEpoch()
        || "RESYNCING".equals(snapshot.orElseThrow().state().stateQuality())) {
      return;
    }
    migration.businessValidation(Instant.now());
    migrations.save(migration);
    resources.recordMigrationPhase(
        migration.getSessionId(),
        migration.getMigrationId(),
        "BUSINESS_VALIDATION",
        "CURRENT_STATE_AVAILABLE",
        false,
        false);
  }

  private void validateBusinessRecovery(SessionMigrationEntity migration) {
    if (browserStates.find(migration.getSessionId()).isEmpty()) {
      throw new MigrationRejectedException("BUSINESS_STATE_MISSING");
    }
    var verdict =
        recoveryService.validateForMigration(
            migration.getSessionId(),
            migration.getTenantId(),
            migration.getMigrationId(),
            recoveryActions.attemptCount(migration.getMigrationId()));
    if (!verdict.ready() && recoveryActions.request(migration, verdict)) {
      return;
    }
    if (!verdict.ready()
        && verdict.evidence().stream()
            .anyMatch(
                item ->
                    item.startsWith("PROVIDER_EVIDENCE_MISSING:")
                        || item.startsWith("PROVIDER_EVIDENCE_EXPIRED:")
                        || item.startsWith("PROVIDER_EVIDENCE_UNKNOWN:"))) {
      // Keep the Browser and Agent paused in BUSINESS_VALIDATION. A trusted Adapter submission
      // changes the evidence revision in the validation idempotency key, so the scheduler can
      // evaluate a fresh verdict without replaying the stale missing-evidence result.
      return;
    }
    migration.complete(verdict.verdict().name(), verdict.ready(), Instant.now());
    migrations.save(migration);
    resources.recordMigrationPhase(
        migration.getSessionId(),
        migration.getMigrationId(),
        verdict.ready() ? "COMPLETED" : "DEGRADED",
        verdict.verdict().name(),
        true,
        verdict.ready());
  }

  private io.browsercloud.domain.session.SessionContext requireTenant(
      String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    if (!session.tenantId().equals(tenantId)) {
      throw new MigrationRejectedException("MIGRATION_SESSION_NOT_FOUND");
    }
    return session;
  }

  private io.browsercloud.domain.session.SessionContext requireTenantForUpdate(
      String sessionId, String tenantId) {
    var session = sessions.requireForUpdate(sessionId);
    if (!session.tenantId().equals(tenantId)) {
      throw new MigrationRejectedException("MIGRATION_SESSION_NOT_FOUND");
    }
    return session;
  }

  private SessionMigrationView toView(SessionMigrationEntity migration) {
    return new SessionMigrationView(
        migration.getMigrationId(),
        migration.getSessionId(),
        migration.getSourceNodeId(),
        migration.getTargetNodeId(),
        migration.getSourceContextEpoch(),
        migration.getTargetContextEpoch(),
        migration.getCheckpointId(),
        migration.getHibernateOperationId(),
        migration.getRestoreOperationId(),
        migration.getResyncRequestId(),
        migration.getPhase(),
        migration.getRecoveryResult(),
        migration.getFailureReason(),
        recoveryActions.attemptCount(migration.getMigrationId()),
        recoveryActions.maximumAttempts(migration.getSessionId(), migration.getTenantId()),
        recoveryActions.latest(migration.getMigrationId()).orElse(null),
        migration.getCreatedAt(),
        migration.getUpdatedAt(),
        migration.getCompletedAt());
  }

  public static final class MigrationRejectedException extends RuntimeException {
    public MigrationRejectedException(String message) {
      super(message);
    }
  }
}
