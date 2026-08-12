package io.browsercloud.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.SessionResourceModels.ResourceAdjustmentView;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.persistence.SessionResourceAdjustmentEntity;
import io.browsercloud.persistence.SessionResourceAdjustmentJpaRepository;
import io.browsercloud.persistence.SessionResourceEventEntity;
import io.browsercloud.persistence.SessionResourceEventJpaRepository;
import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns the durable REQUESTED/EXECUTING/ACKNOWLEDGED/COMMITTED/FAILED resource protocol. */
@Service
public class SessionResourceAdjustmentLifecycleService {
  private static final Set<String> TERMINAL_LATE_ACK_REJECTIONS =
      Set.of(
          "STALE_RESOURCE_OPERATION", "INVALID_RESOURCE_OPERATION_PHASE", "INVALID_SESSION_STATE");

  private final SessionResourceAdjustmentJpaRepository adjustments;
  private final SessionResourcePolicyJpaRepository policies;
  private final SessionResourceEventJpaRepository events;
  private final OperationRepository operations;
  private final ObjectMapper mapper;

  public SessionResourceAdjustmentLifecycleService(
      SessionResourceAdjustmentJpaRepository adjustments,
      SessionResourcePolicyJpaRepository policies,
      SessionResourceEventJpaRepository events,
      OperationRepository operations,
      ObjectMapper mapper) {
    this.adjustments = adjustments;
    this.policies = policies;
    this.events = events;
    this.operations = operations;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requested(
      String operationId,
      String sessionId,
      String tenantId,
      String reason,
      Map<String, Object> oldResources,
      Map<String, Object> requestedResources,
      Instant now) {
    adjustments.save(
        SessionResourceAdjustmentEntity.requested(
            operationId,
            sessionId,
            tenantId,
            reason,
            writeMap(oldResources),
            writeMap(requestedResources),
            now));
  }

  /** Records the first real dispatch attempt before network I/O. */
  @Transactional
  public void executing(String sessionId, String operationId) {
    var adjustment = matchingForUpdate(operationId, sessionId);
    if (adjustment == null) return;
    if (!adjustment.markExecuting(Instant.now())) return;
    operations
        .findActive(sessionId)
        .filter(operation -> operation.operationId().equals(operationId))
        .filter(operation -> operation.mode() == OperationMode.RESOURCE_ADJUSTMENT)
        .filter(operation -> operation.phase() == OperationPhase.PREPARING)
        .ifPresent(
            operation ->
                operations.transitionPhase(
                    operationId, OperationPhase.PREPARING, OperationPhase.EXECUTING));
    adjustments.save(adjustment);
    append(adjustment, "ADJUSTMENT_EXECUTING", "NODE_COMMAND_DISPATCH_STARTED", "EXECUTING");
  }

  /** ACK is recorded before the authority projection is committed. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acknowledged(String sessionId, String operationId, Instant now) {
    var adjustment = matchingForUpdate(operationId, sessionId);
    if (adjustment == null) return;
    if (!adjustment.acknowledge(now)) return;
    adjustments.save(adjustment);
    append(adjustment, "ADJUSTMENT_ACKNOWLEDGED", "NODE_ACTUATOR_ACK_RECEIVED", "ACKNOWLEDGED");
  }

  /**
   * Commits the ledger only after Placement and generic Operation commit in the same transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void committed(String sessionId, String operationId, Instant now) {
    var adjustment = matchingForUpdate(operationId, sessionId);
    if (adjustment == null) return;
    if (!adjustment.commit(now)) return;
    adjustments.save(adjustment);
  }

  /** Dead-lettered dispatches fail promptly and release the Session's active write fence. */
  @Transactional
  public void dispatchFailed(String sessionId, String operationId, String errorCode) {
    var adjustment = adjustments.findForUpdate(operationId).orElse(null);
    if (adjustment == null || !adjustment.getSessionId().equals(sessionId)) return;
    var now = Instant.now();
    if (!adjustment.fail(normalizeError(errorCode), now)) return;
    adjustments.save(adjustment);
    operations
        .findActive(sessionId)
        .filter(operation -> operation.operationId().equals(operationId))
        .filter(operation -> operation.mode() == OperationMode.RESOURCE_ADJUSTMENT)
        .ifPresent(
            operation ->
                operations.transition(operationId, OperationState.ACTIVE, OperationState.ABORTED));
    failPolicy(adjustment, "RESOURCE_ADJUSTMENT_DISPATCH_FAILED:" + normalizeError(errorCode), now);
    append(adjustment, "ADJUSTMENT_FAILED", "NODE_COMMAND_" + normalizeError(errorCode), "FAILED");
  }

  /** Invalid actuator acknowledgements are terminal and must not hold the write fence forever. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acknowledgementFailed(String sessionId, String operationId, String errorCode) {
    var adjustment = adjustments.findForUpdate(operationId).orElse(null);
    if (adjustment == null || !adjustment.getSessionId().equals(sessionId)) {
      abortMatchingResourceOperation(sessionId, operationId);
      return;
    }
    var now = Instant.now();
    var normalized = normalizeError(errorCode);
    if (!adjustment.fail(normalized, now)) return;
    adjustments.save(adjustment);
    abortMatchingResourceOperation(sessionId, operationId);
    failPolicy(adjustment, "RESOURCE_ADJUSTMENT_ACK_REJECTED:" + normalized, now);
    append(adjustment, "ADJUSTMENT_FAILED", normalized, "FAILED");
  }

  /** Classifies an actuator ACK that arrived after this exact adjustment already failed. */
  @Transactional(propagation = Propagation.MANDATORY)
  public LateAcknowledgement lateAcknowledgement(
      String tenantId, String sessionId, String operationId, String coordinatorRejection) {
    if (!TERMINAL_LATE_ACK_REJECTIONS.contains(coordinatorRejection)) {
      return LateAcknowledgement.notConsumed();
    }
    var adjustment = adjustments.findForUpdate(operationId).orElse(null);
    if (adjustment == null
        || !adjustment.getSessionId().equals(sessionId)
        || !adjustment.getTenantId().equals(tenantId)
        || !"FAILED".equals(adjustment.getState())) {
      return LateAcknowledgement.notConsumed();
    }
    if (!"NODE_ACK_TIMEOUT".equals(adjustment.getFailureCode())) {
      append(
          adjustment,
          "LATE_ADJUSTMENT_ACK_IGNORED",
          "FAILED_ADJUSTMENT_TERMINAL:" + normalizeError(coordinatorRejection),
          "IGNORED_AFTER_FAILED");
      return LateAcknowledgement.ignored();
    }
    if (!"STALE_RESOURCE_OPERATION".equals(coordinatorRejection)) {
      append(
          adjustment,
          "LATE_ADJUSTMENT_ACK_IGNORED",
          "SESSION_OR_OPERATION_STATE_CHANGED:" + normalizeError(coordinatorRejection),
          "IGNORED_AFTER_STATE_CHANGE");
      return LateAcknowledgement.ignored();
    }
    var latest = adjustments.findLatestBySessionId(sessionId).orElse(null);
    if (latest == null || !latest.getOperationId().equals(operationId)) {
      reconciliationConflict(adjustment, "SUPERSEDED_BY_NEWER_ADJUSTMENT");
      return LateAcknowledgement.ignored();
    }
    return LateAcknowledgement.reconcile(
        readMap(adjustment.getOldResources()), readMap(adjustment.getRequestedResources()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reconciled(
      String sessionId, String operationId, String reconciliationOperationId, Instant now) {
    var adjustment = matchingForUpdate(operationId, sessionId);
    if (adjustment == null) {
      throw new ResourceAdjustmentLifecycleRejectedException("LEDGER_MISSING");
    }
    var latest = adjustments.findLatestBySessionId(sessionId).orElse(null);
    if (latest == null || !latest.getOperationId().equals(operationId)) {
      throw new ResourceAdjustmentLifecycleRejectedException("RECONCILIATION_SUPERSEDED");
    }
    if (!adjustment.reconcile(reconciliationOperationId, now)) return;
    adjustments.save(adjustment);
    append(
        adjustment,
        "LATE_ADJUSTMENT_ACK_RECONCILED",
        "NODE_ACK_TIMEOUT_AUTHORITY_RECONCILED:" + reconciliationOperationId,
        "RECONCILED");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reconciliationConflict(String sessionId, String operationId, String reason) {
    var adjustment = adjustments.findForUpdate(operationId).orElse(null);
    if (adjustment == null || !adjustment.getSessionId().equals(sessionId)) return;
    reconciliationConflict(adjustment, reason);
  }

  /** Deadline scanner callback for commands that never produced an authoritative ACK. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void timedOut(String sessionId, String operationId) {
    var adjustment = adjustments.findForUpdate(operationId).orElse(null);
    if (adjustment == null || !adjustment.getSessionId().equals(sessionId)) return;
    var now = Instant.now();
    if (!adjustment.fail("NODE_ACK_TIMEOUT", now)) return;
    adjustments.save(adjustment);
    failPolicy(adjustment, "RESOURCE_ADJUSTMENT_ACK_TIMEOUT", now);
    append(adjustment, "ADJUSTMENT_FAILED", "NODE_ACK_TIMEOUT", "FAILED");
  }

  @Transactional(readOnly = true)
  public ResourceAdjustmentView latest(String sessionId) {
    return adjustments.findLatestBySessionId(sessionId).map(this::toView).orElse(null);
  }

  private void failPolicy(SessionResourceAdjustmentEntity adjustment, String reason, Instant now) {
    policies
        .findById(adjustment.getSessionId())
        .filter(policy -> policy.getTenantId().equals(adjustment.getTenantId()))
        .ifPresent(
            policy -> {
              policy.evaluate(
                  io.browsercloud.domain.resource.ResourcePolicyStatus.OBSERVING, reason, now);
              policies.save(policy);
            });
  }

  private void abortMatchingResourceOperation(String sessionId, String operationId) {
    operations
        .findActive(sessionId)
        .filter(operation -> operation.operationId().equals(operationId))
        .filter(operation -> operation.mode() == OperationMode.RESOURCE_ADJUSTMENT)
        .ifPresent(
            operation ->
                operations.transition(operationId, OperationState.ACTIVE, OperationState.ABORTED));
  }

  private SessionResourceAdjustmentEntity matchingForUpdate(String operationId, String sessionId) {
    // Pending N-1 commands created before V091 legitimately have no lifecycle row.
    var adjustment = adjustments.findForUpdate(operationId).orElse(null);
    if (adjustment == null) return null;
    if (!adjustment.getSessionId().equals(sessionId)) {
      throw new ResourceAdjustmentLifecycleRejectedException("SESSION_MISMATCH");
    }
    return adjustment;
  }

  private void append(
      SessionResourceAdjustmentEntity adjustment, String type, String reason, String result) {
    events.save(
        new SessionResourceEventEntity(
            "re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            adjustment.getSessionId(),
            adjustment.getTenantId(),
            type,
            reason,
            adjustment.getOldResources(),
            adjustment.getRequestedResources(),
            "RESOURCE_ADJUSTMENT_LIFECYCLE",
            adjustment.getOperationId(),
            null,
            result,
            Instant.now()));
  }

  private ResourceAdjustmentView toView(SessionResourceAdjustmentEntity adjustment) {
    return new ResourceAdjustmentView(
        adjustment.getOperationId(),
        adjustment.getState(),
        adjustment.getReason(),
        adjustment.getFailureCode(),
        readMap(adjustment.getOldResources()),
        readMap(adjustment.getRequestedResources()),
        adjustment.getRequestedAt(),
        adjustment.getExecutingAt(),
        adjustment.getAcknowledgedAt(),
        adjustment.getCompletedAt(),
        adjustment.getReconciliationOperationId(),
        adjustment.getReconciledAt(),
        adjustment.getUpdatedAt());
  }

  private void reconciliationConflict(SessionResourceAdjustmentEntity adjustment, String reason) {
    var now = Instant.now();
    policies
        .findById(adjustment.getSessionId())
        .filter(policy -> policy.getTenantId().equals(adjustment.getTenantId()))
        .ifPresent(
            policy -> {
              policy.evaluate(
                  io.browsercloud.domain.resource.ResourcePolicyStatus.CRITICAL,
                  "RESOURCE_AUTHORITY_RECONCILIATION_REQUIRED:" + normalizeError(reason),
                  now);
              policies.save(policy);
            });
    append(
        adjustment,
        "LATE_ADJUSTMENT_ACK_CONFLICT",
        normalizeError(reason),
        "MANUAL_RECONCILIATION_REQUIRED");
  }

  private String writeMap(Map<String, Object> value) {
    try {
      return mapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Resource adjustment payload serialization failed", exception);
    }
  }

  private Map<String, Object> readMap(String value) {
    if (value == null || value.isBlank()) return Map.of();
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("Resource adjustment payload is invalid", exception);
    }
  }

  private static String normalizeError(String errorCode) {
    return errorCode == null || errorCode.isBlank() ? "NODE_COMMAND_FAILED" : errorCode;
  }

  public static class ResourceAdjustmentLifecycleRejectedException extends RuntimeException {
    public ResourceAdjustmentLifecycleRejectedException(String reason) {
      super("RESOURCE_ADJUSTMENT_LIFECYCLE_" + reason);
    }
  }

  public record LateAcknowledgement(
      boolean consumed,
      boolean reconciliationRequired,
      Map<String, Object> oldResources,
      Map<String, Object> requestedResources) {
    static LateAcknowledgement notConsumed() {
      return new LateAcknowledgement(false, false, Map.of(), Map.of());
    }

    static LateAcknowledgement ignored() {
      return new LateAcknowledgement(true, false, Map.of(), Map.of());
    }

    static LateAcknowledgement reconcile(
        Map<String, Object> oldResources, Map<String, Object> requestedResources) {
      return new LateAcknowledgement(true, true, oldResources, requestedResources);
    }
  }
}
