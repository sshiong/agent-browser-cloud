package io.browsercloud.application;

import static io.browsercloud.api.SafePointModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserTransactionPolicyRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.DurableWorkflowJpaRepository;
import io.browsercloud.persistence.SessionSafetyLeaseJpaRepository;
import io.browsercloud.persistence.SessionSafetySignalEntity;
import io.browsercloud.persistence.SessionSafetySignalJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates durable control-plane state and fresh Browser Node observations into a fail-closed
 * safe point.
 */
@Service
public class SafePointApplicationService {

  static final String NODE_INPUT_SOURCE = "BROWSER_NODE_INPUT_LEDGER";
  static final String NODE_BROWSER_ACTIVITY_SOURCE = "BROWSER_NODE_CDP_ACTIVITY";
  static final String BROWSER_ACTIVITY_CAPABILITY_LABEL = "safePointBrowserActivity";
  static final String BROWSER_ACTIVITY_CAPABILITY_V1 = "cdp-network-v1";
  static final String BROWSER_TRANSACTION_CAPABILITY_LABEL = "safePointBrowserTransactions";
  static final String BROWSER_TRANSACTION_CAPABILITY_V1 = "cdp-transaction-v1";
  static final String BROWSER_TRANSACTION_POLICY_CAPABILITY_LABEL =
      "safePointBrowserTransactionPolicy";
  static final String BROWSER_TRANSACTION_POLICY_CAPABILITY_V1 = "approved-route-v1";
  static final Duration NODE_SIGNAL_TTL = Duration.ofSeconds(15);
  private static final Set<String> INPUT_SIGNALS = Set.of("ACTIVE_INPUT", "ACTIVE_DRAG");
  private static final Set<String> BROWSER_ACTIVITY_SIGNALS =
      Set.of("FILE_UPLOAD_ACTIVE", "FILE_DOWNLOAD_ACTIVE", "FORM_SUBMISSION_ACTIVE");
  private static final Set<String> BROWSER_TRANSACTION_SIGNALS =
      Set.of("SPA_MUTATION_ACTIVE", "PAYMENT_OR_SECURITY_ACTIVE", "CRITICAL_TRANSACTION_ACTIVE");
  private static final Set<String> NODE_SIGNALS =
      Set.of(
          "ACTIVE_INPUT",
          "ACTIVE_DRAG",
          "FILE_UPLOAD_ACTIVE",
          "FILE_DOWNLOAD_ACTIVE",
          "FORM_SUBMISSION_ACTIVE",
          "SPA_MUTATION_ACTIVE",
          "PAYMENT_OR_SECURITY_ACTIVE",
          "CRITICAL_TRANSACTION_ACTIVE");
  private static final Set<String> BLOCKING_TASK_STATES = Set.of("RUNNING", "WAITING_FOR_HUMAN");
  private static final Set<String> ACTIVE_WORKFLOW_STATES =
      Set.of("PENDING", "DISPATCHED", "RUNNING", "COMPLETING", "COMPENSATING");

  private final SessionRepository sessions;
  private final BrowserPlacementJpaRepository placements;
  private final BrowserNodeJpaRepository browserNodes;
  private final SessionSafetySignalJpaRepository signals;
  private final ExclusiveOperationJpaRepository operations;
  private final AgentTaskJpaRepository tasks;
  private final DurableWorkflowJpaRepository workflows;
  private final SessionSafetyLeaseJpaRepository applicationLeases;
  private final ObjectMapper objectMapper;
  private final BrowserTransactionPolicyRepository browserTransactionPolicies;

  @Autowired
  public SafePointApplicationService(
      SessionRepository sessions,
      BrowserPlacementJpaRepository placements,
      BrowserNodeJpaRepository browserNodes,
      SessionSafetySignalJpaRepository signals,
      ExclusiveOperationJpaRepository operations,
      AgentTaskJpaRepository tasks,
      DurableWorkflowJpaRepository workflows,
      SessionSafetyLeaseJpaRepository applicationLeases,
      BrowserTransactionPolicyRepository browserTransactionPolicies,
      ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.placements = placements;
    this.browserNodes = browserNodes;
    this.signals = signals;
    this.operations = operations;
    this.tasks = tasks;
    this.workflows = workflows;
    this.applicationLeases = applicationLeases;
    this.browserTransactionPolicies = browserTransactionPolicies;
    this.objectMapper = objectMapper;
  }

  /** N-1 source compatibility for isolated tests and embedders without Site Policy. */
  public SafePointApplicationService(
      SessionRepository sessions,
      BrowserPlacementJpaRepository placements,
      BrowserNodeJpaRepository browserNodes,
      SessionSafetySignalJpaRepository signals,
      ExclusiveOperationJpaRepository operations,
      AgentTaskJpaRepository tasks,
      DurableWorkflowJpaRepository workflows,
      SessionSafetyLeaseJpaRepository applicationLeases,
      ObjectMapper objectMapper) {
    this(
        sessions,
        placements,
        browserNodes,
        signals,
        operations,
        tasks,
        workflows,
        applicationLeases,
        (sessionId, tenantId) -> io.browsercloud.coordinator.BrowserTransactionPolicy.empty(),
        objectMapper);
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
    if (observation == null || observation.observedAt() == null) {
      throw new SafetySignalRejectedException("INVALID_SAFETY_OBSERVATION");
    }
    var now = Instant.now();
    if (observation.observedAt().isAfter(now.plusSeconds(30))) {
      throw new SafetySignalRejectedException("SAFETY_TIMESTAMP_IN_FUTURE");
    }
    validateCompleteObservation(observation);
    // A resource report and a VNC input report can update the same five signal rows concurrently.
    // Serialize the whole per-Session observation batch so optimistic versions and first inserts
    // remain coherent without dropping otherwise valid Browser Node telemetry.
    signals.lockSessionObservations(sessionId);
    var expiresAt = observation.observedAt().plus(NODE_SIGNAL_TTL);
    if (observation.hasInputObservation()) {
      var details =
          "{\"pressedKeyCount\":%d,\"pressedButtonCount\":%d}"
              .formatted(observation.pressedKeyCount(), observation.pressedButtonCount());
      observe(
          sessionId,
          tenantId,
          nodeId,
          contextEpoch,
          "ACTIVE_INPUT",
          Boolean.TRUE.equals(observation.inputActive()),
          NODE_INPUT_SOURCE,
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
          Boolean.TRUE.equals(observation.activeDrag()),
          NODE_INPUT_SOURCE,
          details,
          observation.observedAt(),
          expiresAt,
          now);
    }
    if (observation.hasBrowserActivityObservation()) {
      var details =
          ("{\"activeUploadCount\":%d,\"activeDownloadCount\":%d,"
                  + "\"activeFormSubmissionCount\":%d}")
              .formatted(
                  observation.activeUploadCount(),
                  observation.activeDownloadCount(),
                  observation.activeFormSubmissionCount());
      observe(
          sessionId,
          tenantId,
          nodeId,
          contextEpoch,
          "FILE_UPLOAD_ACTIVE",
          observation.activeUploadCount() > 0,
          NODE_BROWSER_ACTIVITY_SOURCE,
          details,
          observation.observedAt(),
          expiresAt,
          now);
      observe(
          sessionId,
          tenantId,
          nodeId,
          contextEpoch,
          "FILE_DOWNLOAD_ACTIVE",
          observation.activeDownloadCount() > 0,
          NODE_BROWSER_ACTIVITY_SOURCE,
          details,
          observation.observedAt(),
          expiresAt,
          now);
      observe(
          sessionId,
          tenantId,
          nodeId,
          contextEpoch,
          "FORM_SUBMISSION_ACTIVE",
          observation.activeFormSubmissionCount() > 0,
          NODE_BROWSER_ACTIVITY_SOURCE,
          details,
          observation.observedAt(),
          expiresAt,
          now);
    }
    if (observation.hasBrowserTransactionObservation()) {
      var details =
          ("{\"activeSpaMutationCount\":%d,\"activePaymentOrSecurityCount\":%d,"
                  + "\"activeCriticalTransactionCount\":%d}")
              .formatted(
                  observation.activeSpaMutationCount(),
                  observation.activePaymentOrSecurityCount(),
                  observation.activeCriticalTransactionCount());
      observe(
          sessionId,
          tenantId,
          nodeId,
          contextEpoch,
          "SPA_MUTATION_ACTIVE",
          observation.activeSpaMutationCount() > 0,
          NODE_BROWSER_ACTIVITY_SOURCE,
          details,
          observation.observedAt(),
          expiresAt,
          now);
      observe(
          sessionId,
          tenantId,
          nodeId,
          contextEpoch,
          "PAYMENT_OR_SECURITY_ACTIVE",
          observation.activePaymentOrSecurityCount() > 0,
          NODE_BROWSER_ACTIVITY_SOURCE,
          details,
          observation.observedAt(),
          expiresAt,
          now);
      observe(
          sessionId,
          tenantId,
          nodeId,
          contextEpoch,
          "CRITICAL_TRANSACTION_ACTIVE",
          observation.activeCriticalTransactionCount() > 0,
          NODE_BROWSER_ACTIVITY_SOURCE,
          details,
          observation.observedAt(),
          expiresAt,
          now);
    }
  }

  @Transactional(readOnly = true)
  public SessionSafePointView assess(String sessionId, String tenantId) {
    var session = requireTenant(sessionId, tenantId);
    var now = Instant.now();
    var blockers = new ArrayList<SafePointBlockerView>();
    var expectedNodeSignals = new HashSet<>(INPUT_SIGNALS);
    if (supportsBrowserActivityObservation(session.nodeId())) {
      expectedNodeSignals.addAll(BROWSER_ACTIVITY_SIGNALS);
    }
    if (supportsBrowserTransactionObservation(session.nodeId())) {
      expectedNodeSignals.addAll(BROWSER_TRANSACTION_SIGNALS);
    }
    var requiresNodeObservation =
        placements
            .findById(sessionId)
            .filter(placement -> !"RELEASED".equals(placement.getState()))
            .isPresent();
    var transactionPolicy = browserTransactionPolicies.find(sessionId, tenantId);
    var requiresTransactionPolicy =
        !transactionPolicy.paymentSecurityRoutePrefixes().isEmpty()
            || !transactionPolicy.criticalTransactionRoutePrefixes().isEmpty();
    var transactionPolicyUnsupported =
        requiresNodeObservation
            && requiresTransactionPolicy
            && !supportsBrowserTransactionPolicy(session.nodeId());
    var nodeSignals =
        signals.findAllBySessionId(sessionId).stream()
            .filter(signal -> NODE_SIGNALS.contains(signal.getSignalType()))
            .filter(this::hasExpectedNodeSource)
            .toList();

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
        expectedNodeSignals.stream()
            .filter(
                expected ->
                    currentNodeSignals.stream()
                        .noneMatch(signal -> expected.equals(signal.getSignalType())))
            .sorted()
            .toList();
    var hasExpiredNodeSignal =
        currentNodeSignals.stream()
            .filter(signal -> expectedNodeSignals.contains(signal.getSignalType()))
            .anyMatch(signal -> !signal.getExpiresAt().isAfter(now));
    if (requiresNodeObservation && !missingSignalTypes.isEmpty()) {
      blockers.add(
          blocker(
              "NODE_SAFETY_SIGNAL_MISSING",
              "BROWSER_NODE_SAFETY_OBSERVER",
              "Required observations missing: " + String.join(", ", missingSignalTypes),
              null,
              null));
    } else if (requiresNodeObservation && hasExpiredNodeSignal) {
      blockers.add(
          blocker(
              "NODE_SAFETY_SIGNAL_STALE",
              "BROWSER_NODE_SAFETY_OBSERVER",
              "Browser Node safety observations expired",
              latestNodeObservation,
              currentNodeSignals.stream()
                  .filter(signal -> expectedNodeSignals.contains(signal.getSignalType()))
                  .map(SessionSafetySignalEntity::getExpiresAt)
                  .min(Comparator.naturalOrder())
                  .orElse(null)));
    }
    if (transactionPolicyUnsupported) {
      blockers.add(
          blocker(
              "BROWSER_TRANSACTION_POLICY_UNSUPPORTED",
              "BROWSER_NODE_SAFETY_OBSERVER",
              "Bound transaction policy requires approved-route-v1 Node capability",
              null,
              null));
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

    applicationLeases
        .findAllBySessionIdAndContextEpochAndState(sessionId, session.contextEpoch(), "ACTIVE")
        .stream()
        .filter(lease -> lease.getExpiresAt().isAfter(now))
        .forEach(
            lease ->
                blockers.add(
                    blocker(
                        lease.getSignalType(),
                        SessionSafetyLeaseApplicationService.APPLICATION_LEASE_SOURCE,
                        lease.getReasonCode() + ":" + lease.getLeaseId(),
                        lease.getRenewedAt(),
                        lease.getExpiresAt())));

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
            : !missingSignalTypes.isEmpty() || transactionPolicyUnsupported
                ? "MISSING"
                : hasExpiredNodeSignal ? "STALE" : "LIVE";
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

  private static void validateCompleteObservation(NodeSafetyObservation observation) {
    if (!observation.hasInputObservation() && !observation.hasBrowserActivityObservation()) {
      if (!observation.hasBrowserTransactionObservation()) {
        throw new SafetySignalRejectedException("EMPTY_SAFETY_OBSERVATION");
      }
    }
    if (observation.hasInputObservation()
        && (observation.inputActive() == null
            || observation.activeDrag() == null
            || observation.pressedKeyCount() == null
            || observation.pressedButtonCount() == null)) {
      throw new SafetySignalRejectedException("INCOMPLETE_INPUT_OBSERVATION");
    }
    if (observation.hasBrowserActivityObservation()
        && (observation.activeUploadCount() == null
            || observation.activeDownloadCount() == null
            || observation.activeFormSubmissionCount() == null)) {
      throw new SafetySignalRejectedException("INCOMPLETE_BROWSER_ACTIVITY_OBSERVATION");
    }
    if (observation.hasBrowserTransactionObservation()
        && (observation.activeSpaMutationCount() == null
            || observation.activePaymentOrSecurityCount() == null
            || observation.activeCriticalTransactionCount() == null)) {
      throw new SafetySignalRejectedException("INCOMPLETE_BROWSER_TRANSACTION_OBSERVATION");
    }
    if ((observation.pressedKeyCount() != null && observation.pressedKeyCount() < 0)
        || (observation.pressedButtonCount() != null && observation.pressedButtonCount() < 0)
        || (observation.activeUploadCount() != null && observation.activeUploadCount() < 0)
        || (observation.activeDownloadCount() != null && observation.activeDownloadCount() < 0)
        || (observation.activeFormSubmissionCount() != null
            && observation.activeFormSubmissionCount() < 0)
        || (observation.activeSpaMutationCount() != null
            && observation.activeSpaMutationCount() < 0)
        || (observation.activePaymentOrSecurityCount() != null
            && observation.activePaymentOrSecurityCount() < 0)
        || (observation.activeCriticalTransactionCount() != null
            && observation.activeCriticalTransactionCount() < 0)) {
      throw new SafetySignalRejectedException("NEGATIVE_SAFETY_COUNT");
    }
  }

  private boolean supportsBrowserActivityObservation(String nodeId) {
    return nodeHasCapability(
        nodeId, BROWSER_ACTIVITY_CAPABILITY_LABEL, BROWSER_ACTIVITY_CAPABILITY_V1);
  }

  private boolean supportsBrowserTransactionObservation(String nodeId) {
    return nodeHasCapability(
        nodeId, BROWSER_TRANSACTION_CAPABILITY_LABEL, BROWSER_TRANSACTION_CAPABILITY_V1);
  }

  private boolean supportsBrowserTransactionPolicy(String nodeId) {
    return nodeHasCapability(
        nodeId,
        BROWSER_TRANSACTION_POLICY_CAPABILITY_LABEL,
        BROWSER_TRANSACTION_POLICY_CAPABILITY_V1);
  }

  private boolean nodeHasCapability(String nodeId, String label, String version) {
    if (nodeId == null) {
      return false;
    }
    return browserNodes
        .findById(nodeId)
        .map(
            node -> {
              try {
                Map<String, String> labels =
                    objectMapper.readValue(
                        node.getLabels(), new TypeReference<Map<String, String>>() {});
                return version.equals(labels.get(label));
              } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                    "persisted Browser Node labels are invalid", exception);
              }
            })
        .orElse(false);
  }

  private boolean hasExpectedNodeSource(SessionSafetySignalEntity signal) {
    return switch (signal.getSignalType()) {
      case "ACTIVE_INPUT", "ACTIVE_DRAG" -> NODE_INPUT_SOURCE.equals(signal.getSource());
      case "FILE_UPLOAD_ACTIVE", "FILE_DOWNLOAD_ACTIVE", "FORM_SUBMISSION_ACTIVE" ->
          NODE_BROWSER_ACTIVITY_SOURCE.equals(signal.getSource());
      case "SPA_MUTATION_ACTIVE", "PAYMENT_OR_SECURITY_ACTIVE", "CRITICAL_TRANSACTION_ACTIVE" ->
          NODE_BROWSER_ACTIVITY_SOURCE.equals(signal.getSource());
      default -> false;
    };
  }

  private void observe(
      String sessionId,
      String tenantId,
      String nodeId,
      long contextEpoch,
      String signalType,
      boolean active,
      String source,
      String details,
      Instant observedAt,
      Instant expiresAt,
      Instant now) {
    var signal =
        signals
            .findBySessionIdAndSignalTypeAndSource(sessionId, signalType, source)
            .orElseGet(
                () ->
                    new SessionSafetySignalEntity(
                        "sfs_" + UUID.randomUUID().toString().replace("-", ""),
                        sessionId,
                        tenantId,
                        nodeId,
                        contextEpoch,
                        signalType,
                        source,
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
