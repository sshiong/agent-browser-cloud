package io.browsercloud.application;

import static io.browsercloud.api.SafePointModels.*;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.SessionSafetyLeaseEntity;
import io.browsercloud.persistence.SessionSafetyLeaseEventEntity;
import io.browsercloud.persistence.SessionSafetyLeaseEventJpaRepository;
import io.browsercloud.persistence.SessionSafetyLeaseJpaRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-bound application business activity leases consumed by the Safe Point Aggregator. */
@Service
public class SessionSafetyLeaseApplicationService {

  static final String APPLICATION_LEASE_SOURCE = "APPLICATION_SAFETY_LEASE";
  private static final Set<String> LIFECYCLE_OPERATION_MODES =
      Set.of(
          "QUIESCE",
          "SNAPSHOT",
          "HIBERNATE",
          "RECOVERY",
          "PROXY_TRANSITION",
          "EXTENSION_MAINTENANCE",
          "TERMINATION");

  private final SessionRepository sessions;
  private final SessionSafetyLeaseJpaRepository leases;
  private final SessionSafetyLeaseEventJpaRepository events;
  private final ExclusiveOperationJpaRepository operations;
  private final IdempotencyService idempotency;

  public SessionSafetyLeaseApplicationService(
      SessionRepository sessions,
      SessionSafetyLeaseJpaRepository leases,
      SessionSafetyLeaseEventJpaRepository events,
      ExclusiveOperationJpaRepository operations,
      IdempotencyService idempotency) {
    this.sessions = sessions;
    this.leases = leases;
    this.events = events;
    this.operations = operations;
    this.idempotency = idempotency;
  }

  @Transactional
  public SafetyLeaseView acquire(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      CreateSafetyLeaseRequest request) {
    var session = requireTenantForUpdate(sessionId, tenantId);
    ensureSessionCanAcquire(session.state(), sessionId);
    var candidateLeaseId = newId("sfl_");
    var leaseId =
        idempotency.claimSafetyLease(
            tenantId, sessionId, actorId, idempotencyKey, request, candidateLeaseId);
    var existing = leases.findById(leaseId);
    if (existing.isPresent()) return toView(existing.orElseThrow(), Instant.now());

    var now = Instant.now();
    var lease =
        leases.save(
            new SessionSafetyLeaseEntity(
                leaseId,
                sessionId,
                tenantId,
                session.contextEpoch(),
                request.signalType().name(),
                request.reasonCode(),
                actorId,
                now,
                now.plusSeconds(request.ttlSeconds())));
    appendEvent(newId("sle_"), lease, "ACQUIRED", now);
    return toView(lease, now);
  }

  @Transactional
  public SafetyLeaseView renew(
      String sessionId,
      String leaseId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      RenewSafetyLeaseRequest request) {
    var session = requireTenantForUpdate(sessionId, tenantId);
    ensureSessionCanAcquire(session.state(), sessionId);
    var lease = requireOwnedLeaseForUpdate(sessionId, leaseId, tenantId, actorId);
    if (lease.getContextEpoch() != session.contextEpoch()) {
      throw new SafetyLeaseRejectedException("STALE_SAFETY_LEASE_CONTEXT");
    }
    var candidateEventId = newId("sle_");
    var eventId =
        idempotency.claimSafetyLeaseMutation(
            tenantId, leaseId, actorId, "RENEW", idempotencyKey, request, candidateEventId);
    if (events.existsById(eventId)) return toView(lease, Instant.now());

    var now = Instant.now();
    if (!"ACTIVE".equals(lease.getState()) || !lease.getExpiresAt().isAfter(now)) {
      throw new SafetyLeaseRejectedException("SAFETY_LEASE_EXPIRED");
    }
    lease.renew(now, now.plusSeconds(request.ttlSeconds()));
    leases.save(lease);
    appendEvent(eventId, lease, "RENEWED", now);
    return toView(lease, now);
  }

  @Transactional
  public SafetyLeaseView release(
      String sessionId, String leaseId, String tenantId, String actorId, String idempotencyKey) {
    requireTenantForUpdate(sessionId, tenantId);
    var lease = requireOwnedLeaseForUpdate(sessionId, leaseId, tenantId, actorId);
    var candidateEventId = newId("sle_");
    var eventId =
        idempotency.claimSafetyLeaseMutation(
            tenantId, leaseId, actorId, "RELEASE", idempotencyKey, leaseId, candidateEventId);
    if (events.existsById(eventId)) return toView(lease, Instant.now());

    var now = Instant.now();
    if (lease.release(now)) {
      leases.save(lease);
      appendEvent(eventId, lease, "EXPIRED".equals(lease.getState()) ? "EXPIRED" : "RELEASED", now);
    }
    return toView(lease, now);
  }

  @Transactional(readOnly = true)
  public SafetyLeaseListResponse list(String sessionId, String tenantId, int limit) {
    requireTenant(sessionId, tenantId);
    var now = Instant.now();
    var items =
        leases
            .findAllBySessionIdOrderByAcquiredAtDesc(
                sessionId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
            .stream()
            .map(lease -> toView(lease, now))
            .toList();
    return new SafetyLeaseListResponse(items, leases.countBySessionId(sessionId));
  }

  @Transactional
  public int expireDueLeases() {
    var now = Instant.now();
    var expired = 0;
    for (var leaseId : leases.lockExpiredLeaseIds(now, 100)) {
      var lease = leases.findById(leaseId).orElseThrow();
      if (!lease.expire(now)) continue;
      leases.save(lease);
      appendEvent(newId("sle_"), lease, "EXPIRED", now);
      expired++;
    }
    return expired;
  }

  private io.browsercloud.domain.session.SessionContext requireTenantForUpdate(
      String sessionId, String tenantId) {
    var session = sessions.requireForUpdate(sessionId);
    if (!tenantId.equals(session.tenantId())) throw new SafetyLeaseNotFoundException();
    return session;
  }

  private io.browsercloud.domain.session.SessionContext requireTenant(
      String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    if (!tenantId.equals(session.tenantId())) throw new SafetyLeaseNotFoundException();
    return session;
  }

  private void ensureSessionCanAcquire(SessionState state, String sessionId) {
    if (state != SessionState.RUNNING && state != SessionState.DEGRADED) {
      throw new SafetyLeaseRejectedException("SAFETY_LEASE_REQUIRES_RUNNING_SESSION");
    }
    operations
        .findBySessionIdAndState(sessionId, "ACTIVE")
        .filter(operation -> LIFECYCLE_OPERATION_MODES.contains(operation.getMode()))
        .ifPresent(
            operation -> {
              throw new SafetyLeaseRejectedException(
                  "SAFETY_LEASE_BLOCKED_BY_" + operation.getMode());
            });
  }

  private SessionSafetyLeaseEntity requireOwnedLeaseForUpdate(
      String sessionId, String leaseId, String tenantId, String actorId) {
    return leases
        .findByIdForUpdate(leaseId)
        .filter(lease -> sessionId.equals(lease.getSessionId()))
        .filter(lease -> tenantId.equals(lease.getTenantId()))
        .filter(lease -> actorId.equals(lease.getOwnerActorId()))
        .orElseThrow(SafetyLeaseNotFoundException::new);
  }

  private void appendEvent(
      String eventId, SessionSafetyLeaseEntity lease, String eventType, Instant now) {
    events.save(
        new SessionSafetyLeaseEventEntity(
            eventId,
            lease.getLeaseId(),
            lease.getSessionId(),
            lease.getTenantId(),
            eventType,
            lease.getExpiresAt(),
            now));
  }

  static SafetyLeaseView toView(SessionSafetyLeaseEntity lease, Instant now) {
    var state =
        "ACTIVE".equals(lease.getState()) && !lease.getExpiresAt().isAfter(now)
            ? "EXPIRED"
            : lease.getState();
    return new SafetyLeaseView(
        lease.getLeaseId(),
        lease.getSessionId(),
        lease.getContextEpoch(),
        ApplicationSafetySignalType.valueOf(lease.getSignalType()),
        lease.getReasonCode(),
        lease.getOwnerActorId(),
        state,
        lease.getAcquiredAt(),
        lease.getRenewedAt(),
        lease.getExpiresAt(),
        lease.getReleasedAt());
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  public static final class SafetyLeaseNotFoundException extends RuntimeException {}

  public static final class SafetyLeaseRejectedException extends RuntimeException {
    public SafetyLeaseRejectedException(String code) {
      super(code);
    }
  }
}
