package io.browsercloud.application;

import static io.browsercloud.api.SafePointModels.*;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.DurableWorkflowJpaRepository;
import io.browsercloud.persistence.SessionSafetySignalEntity;
import io.browsercloud.persistence.SessionSafetySignalJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates durable control-plane state and fresh Browser Node observations into a fail-closed
 * safe point.
 */
@Service
public class SafePointApplicationService {

  static final String NODE_INPUT_SOURCE = "BROWSER_NODE_INPUT_LEDGER";
  static final Duration NODE_SIGNAL_TTL = Duration.ofSeconds(15);
  private static final Set<String> NODE_SIGNALS = Set.of("ACTIVE_INPUT", "ACTIVE_DRAG");
  private static final Set<String> BLOCKING_TASK_STATES = Set.of("RUNNING", "WAITING_FOR_HUMAN");
  private static final Set<String> ACTIVE_WORKFLOW_STATES =
      Set.of("PENDING", "DISPATCHED", "RUNNING", "COMPLETING", "COMPENSATING");

  private final SessionRepository sessions;
  private final BrowserPlacementJpaRepository placements;
  private final SessionSafetySignalJpaRepository signals;
  private final ExclusiveOperationJpaRepository operations;
  private final AgentTaskJpaRepository tasks;
  private final DurableWorkflowJpaRepository workflows;

  public SafePointApplicationService(
      SessionRepository sessions,
      BrowserPlacementJpaRepository placements,
      SessionSafetySignalJpaRepository signals,
      ExclusiveOperationJpaRepository operations,
      AgentTaskJpaRepository tasks,
      DurableWorkflowJpaRepository workflows) {
    this.sessions = sessions;
    this.placements = placements;
    this.signals = signals;
    this.operations = operations;
    this.tasks = tasks;
    this.workflows = workflows;
  }

  @Transactional
  public void recordNodeObservation(
      String sessionId,
      String tenantId,
      String nodeId,
      long contextEpoch,
      NodeSafetyObservation observation) {
    var session = requireTenant(sessionId, tenantId);
    if (session.contextEpoch() != contextEpoch) {
      throw new SafetySignalRejectedException("STALE_SAFETY_CONTEXT");
    }
    if (session.nodeId() == null || !session.nodeId().equals(nodeId)) {
      throw new SafetySignalRejectedException("SAFETY_NODE_MISMATCH");
    }
    var now = Instant.now();
    if (observation.observedAt().isAfter(now.plusSeconds(30))) {
      throw new SafetySignalRejectedException("SAFETY_TIMESTAMP_IN_FUTURE");
    }
    var expiresAt = observation.observedAt().plus(NODE_SIGNAL_TTL);
    var details =
        "{\"pressedKeyCount\":%d,\"pressedButtonCount\":%d}"
            .formatted(observation.pressedKeyCount(), observation.pressedButtonCount());
    observe(
        sessionId,
        tenantId,
        nodeId,
        contextEpoch,
        "ACTIVE_INPUT",
        observation.inputActive(),
        details,
        observation.observedAt(),
        expiresAt,
        now);
    observe(
        sessionId,
        tenantId,
        nodeId,
        contextEpoch,
        "ACTIVE_DRAG",
        observation.activeDrag(),
        details,
        observation.observedAt(),
        expiresAt,
        now);
  }

  @Transactional(readOnly = true)
  public SessionSafePointView assess(String sessionId, String tenantId) {
    var session = requireTenant(sessionId, tenantId);
    var now = Instant.now();
    var blockers = new ArrayList<SafePointBlockerView>();
    var nodeSignals =
        signals.findAllBySessionId(sessionId).stream()
            .filter(signal -> NODE_SIGNALS.contains(signal.getSignalType()))
            .filter(signal -> NODE_INPUT_SOURCE.equals(signal.getSource()))
            .toList();

    var requiresNodeObservation =
        placements
            .findById(sessionId)
            .filter(placement -> !"RELEASED".equals(placement.getState()))
            .isPresent();
    var currentNodeSignals =
        nodeSignals.stream()
            .filter(signal -> signal.getContextEpoch() == session.contextEpoch())
            .filter(
                signal -> session.nodeId() != null && session.nodeId().equals(signal.getNodeId()))
            .toList();
    var latestNodeObservation =
        currentNodeSignals.stream()
            .map(SessionSafetySignalEntity::getObservedAt)
            .max(Comparator.naturalOrder())
            .orElse(null);

    var missingSignalTypes =
        NODE_SIGNALS.stream()
            .filter(
                expected ->
                    currentNodeSignals.stream()
                        .noneMatch(signal -> expected.equals(signal.getSignalType())))
            .sorted()
            .toList();
    var hasExpiredNodeSignal =
        currentNodeSignals.stream().anyMatch(signal -> !signal.getExpiresAt().isAfter(now));
    if (requiresNodeObservation && !missingSignalTypes.isEmpty()) {
      blockers.add(
          blocker(
              "NODE_SAFETY_SIGNAL_MISSING",
              NODE_INPUT_SOURCE,
              "Required observations missing: " + String.join(", ", missingSignalTypes),
              null,
              null));
    } else if (requiresNodeObservation && hasExpiredNodeSignal) {
      blockers.add(
          blocker(
              "NODE_SAFETY_SIGNAL_STALE",
              NODE_INPUT_SOURCE,
              "Browser Node input observations expired",
              latestNodeObservation,
              currentNodeSignals.stream()
                  .map(SessionSafetySignalEntity::getExpiresAt)
                  .min(Comparator.naturalOrder())
                  .orElse(null)));
    }

    currentNodeSignals.stream()
        .filter(signal -> signal.getExpiresAt().isAfter(now))
        .filter(SessionSafetySignalEntity::isActive)
        .forEach(
            signal ->
                blockers.add(
                    blocker(
                        signal.getSignalType(),
                        signal.getSource(),
                        signal.getDetails(),
                        signal.getObservedAt(),
                        signal.getExpiresAt())));

    operations
        .findBySessionIdAndState(sessionId, "ACTIVE")
        .ifPresent(
            operation ->
                blockers.add(
                    blocker(
                        "HUMAN_TAKEOVER".equals(operation.getMode())
                            ? "HUMAN_TAKEOVER_ACTIVE"
                            : "EXCLUSIVE_OPERATION_ACTIVE",
                        "CONTROL_PLANE_OPERATION",
                        operation.getMode() + ":" + operation.getPhase(),
                        operation.getCreatedAt(),
                        operation.getDeadline())));

    tasks
        .findAllBySessionIdAndStateIn(sessionId, BLOCKING_TASK_STATES)
        .forEach(
            task ->
                blockers.add(
                    blocker(
                        "WAITING_FOR_HUMAN".equals(task.getState())
                            ? "HUMAN_HANDOFF_PENDING"
                            : "AGENT_TASK_ACTIVE",
                        "AGENT_TASK",
                        task.getTaskId()
                            + (task.getPendingToolId() == null
                                ? ""
                                : ":" + task.getPendingToolId()),
                        task.getUpdatedAt(),
                        task.getStepDeadlineAt())));

    workflows
        .findAllBySessionIdAndStateIn(sessionId, ACTIVE_WORKFLOW_STATES)
        .forEach(
            workflow ->
                blockers.add(
                    blocker(
                        workflow.getWorkflowType().contains("SNAPSHOT")
                            ? "SNAPSHOT_IN_PROGRESS"
                            : workflow.getWorkflowType().contains("PROFILE")
                                ? "PROFILE_FLUSH_IN_PROGRESS"
                                : "DURABLE_WORKFLOW_ACTIVE",
                        "DURABLE_WORKFLOW",
                        workflow.getWorkflowType() + ":" + workflow.getPhase(),
                        null,
                        workflow.getPhaseDeadline())));

    signals.findAllBySessionId(sessionId).stream()
        .filter(signal -> !NODE_SIGNALS.contains(signal.getSignalType()))
        .filter(signal -> signal.getContextEpoch() == session.contextEpoch())
        .filter(signal -> signal.getExpiresAt().isAfter(now))
        .filter(SessionSafetySignalEntity::isActive)
        .forEach(
            signal ->
                blockers.add(
                    blocker(
                        signal.getSignalType(),
                        signal.getSource(),
                        signal.getDetails(),
                        signal.getObservedAt(),
                        signal.getExpiresAt())));

    blockers.sort(Comparator.comparing(SafePointBlockerView::code));
    var freshness =
        !requiresNodeObservation
            ? "NOT_REQUIRED"
            : !missingSignalTypes.isEmpty() ? "MISSING" : hasExpiredNodeSignal ? "STALE" : "LIVE";
    var safe = blockers.isEmpty();
    return new SessionSafePointView(
        sessionId,
        safe,
        safe
            ? "SAFE"
            : ("LIVE".equals(freshness) || "NOT_REQUIRED".equals(freshness))
                ? "BLOCKED"
                : "UNKNOWN",
        freshness,
        session.nodeId(),
        session.contextEpoch(),
        now,
        latestNodeObservation,
        List.copyOf(blockers));
  }

  private void observe(
      String sessionId,
      String tenantId,
      String nodeId,
      long contextEpoch,
      String signalType,
      boolean active,
      String details,
      Instant observedAt,
      Instant expiresAt,
      Instant now) {
    var signal =
        signals
            .findBySessionIdAndSignalTypeAndSource(sessionId, signalType, NODE_INPUT_SOURCE)
            .orElseGet(
                () ->
                    new SessionSafetySignalEntity(
                        "sfs_" + UUID.randomUUID().toString().replace("-", ""),
                        sessionId,
                        tenantId,
                        nodeId,
                        contextEpoch,
                        signalType,
                        NODE_INPUT_SOURCE,
                        active,
                        details,
                        observedAt,
                        expiresAt,
                        now));
    signal.observe(tenantId, nodeId, contextEpoch, active, details, observedAt, expiresAt, now);
    signals.save(signal);
  }

  private io.browsercloud.domain.session.SessionContext requireTenant(
      String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    if (!session.tenantId().equals(tenantId)) {
      throw new SafePointNotFoundException();
    }
    return session;
  }

  private static SafePointBlockerView blocker(
      String code, String source, String detail, Instant observedAt, Instant expiresAt) {
    return new SafePointBlockerView(code, source, detail, observedAt, expiresAt);
  }

  public static final class SafetySignalRejectedException extends RuntimeException {
    public SafetySignalRejectedException(String code) {
      super(code);
    }
  }

  public static final class SafePointNotFoundException extends RuntimeException {}
}
