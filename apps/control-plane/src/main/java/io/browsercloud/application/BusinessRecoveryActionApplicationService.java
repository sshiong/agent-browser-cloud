package io.browsercloud.application;

import io.browsercloud.api.BusinessRecoveryModels.BusinessRecoveryActionView;
import io.browsercloud.api.BusinessRecoveryModels.BusinessRecoveryValidationView;
import io.browsercloud.api.BusinessRecoveryModels.RecoveryAction;
import io.browsercloud.api.BusinessRecoveryModels.Verdict;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.BusinessRecoveryActionEntity;
import io.browsercloud.persistence.BusinessRecoveryActionJpaRepository;
import io.browsercloud.persistence.SessionMigrationEntity;
import io.browsercloud.persistence.SessionMigrationJpaRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes only bounded, contract-owned, low-risk Business Recovery actions. */
@Service
public class BusinessRecoveryActionApplicationService {

  private static final Set<Verdict> AUTO_RECOVERABLE =
      Set.of(Verdict.APPLICATION_UNAVAILABLE, Verdict.STATE_CHANGED);

  private final ApplicationBusinessRecoveryService recovery;
  private final BusinessRecoveryActionJpaRepository actions;
  private final SessionMigrationJpaRepository migrations;
  private final SessionRepository sessions;
  private final BrowserStateRepository browserStates;
  private final BrowserCapacityApplicationService capacity;
  private final NodeCommandGateway nodeCommands;
  private final SessionResourceApplicationService resources;

  public BusinessRecoveryActionApplicationService(
      ApplicationBusinessRecoveryService recovery,
      BusinessRecoveryActionJpaRepository actions,
      SessionMigrationJpaRepository migrations,
      SessionRepository sessions,
      BrowserStateRepository browserStates,
      BrowserCapacityApplicationService capacity,
      NodeCommandGateway nodeCommands,
      SessionResourceApplicationService resources) {
    this.recovery = recovery;
    this.actions = actions;
    this.migrations = migrations;
    this.sessions = sessions;
    this.browserStates = browserStates;
    this.capacity = capacity;
    this.nodeCommands = nodeCommands;
    this.resources = resources;
  }

  @Transactional
  public boolean request(
      SessionMigrationEntity migration, BusinessRecoveryValidationView validation) {
    if (!AUTO_RECOVERABLE.contains(validation.verdict())) {
      return false;
    }
    var policy =
        recovery.autoRecoveryPolicy(migration.getSessionId(), migration.getTenantId()).orElse(null);
    if (policy == null || policy.action() == RecoveryAction.NONE || policy.maximumAttempts() <= 0) {
      return false;
    }
    int priorAttempts = Math.toIntExact(actions.countByMigrationId(migration.getMigrationId()));
    if (priorAttempts >= policy.maximumAttempts()) {
      return false;
    }

    var session = sessions.requireForUpdate(migration.getSessionId());
    if (!session.tenantId().equals(migration.getTenantId())
        || (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED)) {
      throw new AutoRecoveryRejectedException("AUTO_RECOVERY_REQUIRES_RUNNING_SESSION");
    }
    var snapshot =
        browserStates
            .find(migration.getSessionId())
            .filter(item -> item.tenantId().equals(migration.getTenantId()))
            .filter(item -> item.contextEpoch() == session.contextEpoch())
            .orElseThrow(
                () -> new AutoRecoveryRejectedException("AUTO_RECOVERY_STATE_UNAVAILABLE"));
    if (!capacity.nodeHasCapability(
        session.nodeId(), "businessRecoveryActions", "cdp-low-risk-v1")) {
      return false;
    }
    var targetUrl = targetUrl(policy, snapshot.state().url());
    var now = Instant.now();
    var actionId = newId("bra_");
    var commandId = newId("cmd_");
    var action =
        new BusinessRecoveryActionEntity(
            actionId,
            migration.getMigrationId(),
            migration.getSessionId(),
            migration.getTenantId(),
            policy.contractId(),
            policy.contractVersion(),
            priorAttempts + 1,
            policy.action(),
            targetUrl,
            snapshot.state().stateVersion(),
            commandId,
            now.plusSeconds(30),
            now);
    actions.save(action);
    nodeCommands.send(
        NodeCommands.businessRecoveryAction(
            session,
            commandId,
            actionId,
            policy.action().name(),
            targetUrl,
            snapshot.state().stateVersion()));
    action.executing(now);
    actions.save(action);
    migration.businessRecoveryAction(now);
    migrations.save(migration);
    resources.recordMigrationPhase(
        migration.getSessionId(),
        migration.getMigrationId(),
        "BUSINESS_RECOVERY_ACTION",
        policy.action().name() + ":ATTEMPT_" + (priorAttempts + 1),
        false,
        false);
    return true;
  }

  @Transactional
  public void stateUpdated(NodeEventReceived event, NodeEvent.StateUpdated state) {
    if (!"BUSINESS_RECOVERY_ACTION".equals(state.snapshotKind())
        || state.requestedRootRef() == null
        || !state.requestedRootRef().startsWith("bra_")) {
      return;
    }
    var action = actions.findById(state.requestedRootRef()).orElse(null);
    if (action == null || "COMMITTED".equals(action.getState())) {
      return;
    }
    if (!action.getTenantId().equals(event.tenantId())
        || !action.getSessionId().equals(event.sessionId())
        || state.stateVersion() <= action.getBaseStateVersion()) {
      throw new AutoRecoveryRejectedException("AUTO_RECOVERY_ACK_CONTEXT_MISMATCH");
    }
    var migration = migrations.findById(action.getMigrationId()).orElseThrow();
    if (migration.getTargetContextEpoch() == null
        || event.contextEpoch() != migration.getTargetContextEpoch()) {
      throw new AutoRecoveryRejectedException("AUTO_RECOVERY_ACK_CONTEXT_MISMATCH");
    }
    if (!"BUSINESS_RECOVERY_ACTION".equals(migration.getPhase())) {
      throw new AutoRecoveryRejectedException("AUTO_RECOVERY_ACK_PHASE_MISMATCH");
    }
    var now = Instant.now();
    action.committed(state.stateVersion(), now);
    actions.save(action);
    migration.businessValidation(now);
    migrations.save(migration);
    resources.recordMigrationPhase(
        migration.getSessionId(),
        migration.getMigrationId(),
        "BUSINESS_VALIDATION",
        action.action().name() + ":ACK_COMMITTED",
        false,
        false);
  }

  @Transactional
  public void reconcile(SessionMigrationEntity migration) {
    var action =
        actions
            .findFirstByMigrationIdOrderByAttemptNumberDesc(migration.getMigrationId())
            .orElseThrow(() -> new AutoRecoveryRejectedException("AUTO_RECOVERY_ACTION_MISSING"));
    if ("COMMITTED".equals(action.getState()) || "FAILED".equals(action.getState())) {
      migration.businessValidation(Instant.now());
      migrations.save(migration);
      return;
    }
    var now = Instant.now();
    if (!action.getDeadlineAt().isAfter(now)) {
      action.failed("ACTION_ACK_TIMEOUT", now);
      actions.save(action);
      migration.businessValidation(now);
      migrations.save(migration);
      resources.recordMigrationPhase(
          migration.getSessionId(),
          migration.getMigrationId(),
          "BUSINESS_VALIDATION",
          action.action().name() + ":ACTION_ACK_TIMEOUT",
          false,
          false);
    }
  }

  @Transactional(readOnly = true)
  public int attemptCount(String migrationId) {
    return Math.toIntExact(actions.countByMigrationId(migrationId));
  }

  @Transactional(readOnly = true)
  public Optional<BusinessRecoveryActionView> latest(String migrationId) {
    return actions
        .findFirstByMigrationIdOrderByAttemptNumberDesc(migrationId)
        .map(BusinessRecoveryActionApplicationService::toView);
  }

  @Transactional(readOnly = true)
  public int maximumAttempts(String sessionId, String tenantId) {
    return recovery
        .autoRecoveryPolicy(sessionId, tenantId)
        .map(ApplicationBusinessRecoveryService.AutoRecoveryPolicy::maximumAttempts)
        .orElse(0);
  }

  private static String targetUrl(
      ApplicationBusinessRecoveryService.AutoRecoveryPolicy policy, String currentUrl) {
    return switch (policy.action()) {
      case RELOAD, REFRESH_SESSION -> null;
      case NAVIGATE_HOME -> origin(policy, currentUrl) + "/";
      case REOPEN_KNOWN_ROUTE -> {
        var route =
            policy.readyRoutePrefixes().isEmpty() ? "/" : policy.readyRoutePrefixes().getFirst();
        yield origin(policy, currentUrl) + route;
      }
      case NONE -> throw new AutoRecoveryRejectedException("AUTO_RECOVERY_ACTION_NOT_CONFIGURED");
    };
  }

  private static String origin(
      ApplicationBusinessRecoveryService.AutoRecoveryPolicy policy, String currentUrl) {
    if (currentUrl != null && !currentUrl.isBlank()) {
      try {
        var current = URI.create(currentUrl);
        var origin =
            new URI(
                    current.getScheme(),
                    null,
                    current.getHost(),
                    current.getPort(),
                    null,
                    null,
                    null)
                .toString();
        if (policy.expectedOrigins().contains(origin)) {
          return origin;
        }
      } catch (Exception ignored) {
        // Fall through to the contract-owned origin.
      }
    }
    if (policy.expectedOrigins().isEmpty()) {
      throw new AutoRecoveryRejectedException("AUTO_RECOVERY_EXPECTED_ORIGIN_MISSING");
    }
    return policy.expectedOrigins().getFirst();
  }

  private static BusinessRecoveryActionView toView(BusinessRecoveryActionEntity action) {
    return new BusinessRecoveryActionView(
        action.getActionId(),
        action.getMigrationId(),
        action.getAttemptNumber(),
        action.action(),
        action.getTargetUrl(),
        action.getBaseStateVersion(),
        action.getResultingStateVersion(),
        action.getState(),
        action.getErrorCode(),
        action.getCreatedAt(),
        action.getCompletedAt());
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  public static final class AutoRecoveryRejectedException extends RuntimeException {
    public AutoRecoveryRejectedException(String message) {
      super(message);
    }
  }
}
