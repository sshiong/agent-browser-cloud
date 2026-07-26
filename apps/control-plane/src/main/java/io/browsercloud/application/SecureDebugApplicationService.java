package io.browsercloud.application;

import io.browsercloud.api.SecureDebugSessionListResponse;
import io.browsercloud.api.SecureDebugSessionView;
import io.browsercloud.api.SecureDebugSnapshotView;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.BreakGlassRequestEntity;
import io.browsercloud.persistence.BreakGlassRequestJpaRepository;
import io.browsercloud.persistence.SecureDebugAccessEventEntity;
import io.browsercloud.persistence.SecureDebugAccessEventJpaRepository;
import io.browsercloud.persistence.SecureDebugSessionEntity;
import io.browsercloud.persistence.SecureDebugSessionJpaRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Break-glass-bound, purpose-limited production diagnostics. */
@Service
public class SecureDebugApplicationService {

  static final String SNAPSHOT_PROJECTION =
      "session.state,runtimeBuildId,contextEpoch,browserGeneration,networkRevision,"
          + "url.origin,stateVersion,targetRevision,stateQuality,stateHash,target.counts";

  private final SecureDebugSessionJpaRepository sessionRepository;
  private final SecureDebugAccessEventJpaRepository accessEventRepository;
  private final BreakGlassRequestJpaRepository breakGlassRepository;
  private final BreakGlassApplicationService breakGlassService;
  private final SessionRepository browserSessionRepository;
  private final BrowserStateRepository browserStateRepository;
  private final AuditApplicationService auditService;
  private final long maximumMinutes;

  public SecureDebugApplicationService(
      SecureDebugSessionJpaRepository sessionRepository,
      SecureDebugAccessEventJpaRepository accessEventRepository,
      BreakGlassRequestJpaRepository breakGlassRepository,
      BreakGlassApplicationService breakGlassService,
      SessionRepository browserSessionRepository,
      BrowserStateRepository browserStateRepository,
      AuditApplicationService auditService,
      @Value("${security.secure-debug-max-minutes:15}") long maximumMinutes) {
    if (maximumMinutes < 1 || maximumMinutes > 15) {
      throw new IllegalStateException("Secure Debug maximum duration must be between 1 and 15");
    }
    this.sessionRepository = sessionRepository;
    this.accessEventRepository = accessEventRepository;
    this.breakGlassRepository = breakGlassRepository;
    this.breakGlassService = breakGlassService;
    this.browserSessionRepository = browserSessionRepository;
    this.browserStateRepository = browserStateRepository;
    this.auditService = auditService;
    this.maximumMinutes = maximumMinutes;
  }

  @Transactional(noRollbackFor = SecureDebugRejectedException.class)
  public SecureDebugSessionView start(String breakGlassRequestId, String tenantId, String actorId) {
    if (!breakGlassService.authorize(
        breakGlassRequestId,
        tenantId,
        actorId,
        "SESSION",
        resourceId(breakGlassRequestId, tenantId),
        "SECURE_DEBUG")) {
      throw new SecureDebugRejectedException("BREAK_GLASS_GRANT_NOT_AUTHORIZED");
    }
    var grant = requireGrant(breakGlassRequestId, tenantId);
    requireSecureDebugGrant(grant);
    var browserSession = browserSessionRepository.require(grant.getResourceId());
    if (!browserSession.tenantId().equals(tenantId)) {
      throw new SecureDebugRejectedException("RESOURCE_TENANT_MISMATCH");
    }

    var existing =
        sessionRepository.findByBreakGlassRequestIdAndTenantId(breakGlassRequestId, tenantId);
    if (existing.isPresent()) {
      var session = existing.get();
      if ("ACTIVE".equals(session.getState()) && actorId.equals(session.getOperatorId())) {
        return toView(session);
      }
      throw new SecureDebugRejectedException("BREAK_GLASS_GRANT_ALREADY_CONSUMED");
    }

    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var expiresAt = minimum(grant.getExpiresAt(), now.plus(maximumMinutes, ChronoUnit.MINUTES));
    if (!expiresAt.isAfter(now)) {
      throw new SecureDebugRejectedException("BREAK_GLASS_GRANT_EXPIRED");
    }
    var entity =
        new SecureDebugSessionEntity(
            identifier("dbg_"),
            breakGlassRequestId,
            tenantId,
            grant.getResourceType(),
            grant.getResourceId(),
            actorId,
            now,
            expiresAt);
    entity = sessionRepository.save(entity);
    recordEvent(entity, actorId, "START", "ACTIVE", "session_context_metadata", now);
    sessionRepository.save(entity);
    appendAudit(entity, actorId, "SECURE_DEBUG_STARTED", "ACTIVE", "session_context_metadata");
    return toView(entity);
  }

  @Transactional(readOnly = true)
  public SecureDebugSessionListResponse list(String tenantId) {
    var items =
        sessionRepository.findAllByTenantIdOrderByStartedAtDesc(tenantId).stream()
            .map(SecureDebugApplicationService::toView)
            .toList();
    return new SecureDebugSessionListResponse(items, items.size());
  }

  @Transactional(noRollbackFor = SecureDebugRejectedException.class)
  public SecureDebugSnapshotView snapshot(String debugSessionId, String tenantId, String actorId) {
    var entity = requireForUpdate(debugSessionId, tenantId);
    requireOperator(entity, actorId);
    requireActive(entity, actorId);

    var authorized =
        breakGlassService.authorize(
            entity.getBreakGlassRequestId(),
            tenantId,
            actorId,
            entity.getResourceType(),
            entity.getResourceId(),
            "SECURE_DEBUG");
    if (!authorized) {
      terminate(entity, actorId, "REVOKED", "BREAK_GLASS_GRANT_INVALID", "GRANT_REVOKED");
      throw new SecureDebugRejectedException("BREAK_GLASS_GRANT_NOT_AUTHORIZED");
    }

    var browserSession = browserSessionRepository.require(entity.getResourceId());
    if (!browserSession.tenantId().equals(tenantId)) {
      terminate(entity, actorId, "REVOKED", "RESOURCE_TENANT_MISMATCH", "GRANT_REVOKED");
      throw new SecureDebugRejectedException("RESOURCE_TENANT_MISMATCH");
    }
    var state = browserStateRepository.find(entity.getResourceId()).orElse(null);
    if (state != null && !state.tenantId().equals(tenantId)) {
      terminate(entity, actorId, "REVOKED", "STATE_TENANT_MISMATCH", "GRANT_REVOKED");
      throw new SecureDebugRejectedException("STATE_TENANT_MISMATCH");
    }

    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    entity.recordAccess(now);
    var evidence = recordEvent(entity, actorId, "SNAPSHOT", "MINIMIZED", SNAPSHOT_PROJECTION, now);
    sessionRepository.save(entity);
    appendAudit(
        entity, actorId, "SECURE_DEBUG_SNAPSHOT_ACCESSED", "MINIMIZED", SNAPSHOT_PROJECTION);

    var snapshot = state == null ? null : state.state();
    return new SecureDebugSnapshotView(
        entity.getDebugSessionId(),
        entity.getResourceId(),
        browserSession.state().name(),
        browserSession.runtimeBuildId(),
        browserSession.contextEpoch(),
        browserSession.browserGeneration(),
        browserSession.networkRevision(),
        snapshot == null ? null : origin(snapshot.url()),
        snapshot == null ? 0 : snapshot.stateVersion(),
        snapshot == null ? 0 : snapshot.targetRevision(),
        snapshot == null ? "UNAVAILABLE" : snapshot.stateQuality(),
        snapshot == null ? null : snapshot.stateHash(),
        snapshot == null ? 0 : snapshot.targets().size(),
        snapshot == null
            ? 0
            : (int) snapshot.targets().stream().filter(target -> target.sensitive()).count(),
        now,
        entity.getAccessCount(),
        evidence,
        "SENSITIVE_MINIMIZED",
        SNAPSHOT_PROJECTION);
  }

  @Transactional(noRollbackFor = SecureDebugRejectedException.class)
  public SecureDebugSessionView end(String debugSessionId, String tenantId, String actorId) {
    var entity = requireForUpdate(debugSessionId, tenantId);
    requireOperator(entity, actorId);
    if ("ACTIVE".equals(entity.getState())) {
      terminate(entity, actorId, "ENDED", "OPERATOR_ENDED", "END");
    }
    return toView(entity);
  }

  @Scheduled(fixedDelayString = "${security.secure-debug-scan-interval-ms:5000}")
  @Transactional
  public void terminateInvalidSessions() {
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    for (var entity : sessionRepository.findAllActiveForUpdate()) {
      if (!entity.getExpiresAt().isAfter(now)) {
        terminate(entity, "system", "EXPIRED", "TIME_LIMIT_REACHED", "AUTO_EXPIRE");
        continue;
      }
      var grant = breakGlassRepository.findById(entity.getBreakGlassRequestId()).orElse(null);
      if (grant == null || !"ACTIVE".equals(grant.getState())) {
        terminate(entity, "system", "REVOKED", "BREAK_GLASS_GRANT_INVALID", "GRANT_REVOKED");
      }
    }
  }

  private String resourceId(String breakGlassRequestId, String tenantId) {
    return requireGrant(breakGlassRequestId, tenantId).getResourceId();
  }

  private BreakGlassRequestEntity requireGrant(String breakGlassRequestId, String tenantId) {
    return breakGlassRepository
        .findForUpdate(breakGlassRequestId, tenantId)
        .orElseThrow(SecureDebugNotFoundException::new);
  }

  private static void requireSecureDebugGrant(BreakGlassRequestEntity grant) {
    if (!"SESSION".equals(grant.getResourceType())
        || !"SECURE_DEBUG".equals(grant.getRequestedScope())) {
      throw new SecureDebugRejectedException("GRANT_SCOPE_MUST_BE_SESSION_SECURE_DEBUG");
    }
  }

  private SecureDebugSessionEntity requireForUpdate(String debugSessionId, String tenantId) {
    return sessionRepository
        .findForUpdate(debugSessionId, tenantId)
        .orElseThrow(SecureDebugNotFoundException::new);
  }

  private void requireOperator(SecureDebugSessionEntity entity, String actorId) {
    if (!entity.getOperatorId().equals(actorId)) {
      var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
      recordEvent(
          entity, actorId, "ACCESS_DENIED", "OPERATOR_MISMATCH", "no_sensitive_fields", now);
      sessionRepository.save(entity);
      appendAudit(
          entity,
          actorId,
          "SECURE_DEBUG_ACCESS_DENIED",
          "OPERATOR_MISMATCH",
          "no_sensitive_fields");
      throw new SecureDebugRejectedException("ONLY_AUTHORIZED_OPERATOR_MAY_ACCESS");
    }
  }

  private void requireActive(SecureDebugSessionEntity entity, String actorId) {
    if (!"ACTIVE".equals(entity.getState())) {
      appendAudit(
          entity,
          actorId,
          "SECURE_DEBUG_ACCESS_DENIED",
          "DEBUG_SESSION_" + entity.getState(),
          "no_sensitive_fields");
      throw new SecureDebugRejectedException("DEBUG_SESSION_" + entity.getState());
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    if (!entity.getExpiresAt().isAfter(now)) {
      terminate(entity, "system", "EXPIRED", "TIME_LIMIT_REACHED", "AUTO_EXPIRE");
      throw new SecureDebugRejectedException("DEBUG_SESSION_EXPIRED");
    }
  }

  private void terminate(
      SecureDebugSessionEntity entity, String actorId, String state, String reason, String action) {
    if (!"ACTIVE".equals(entity.getState())) {
      return;
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    entity.end(state, reason, now);
    recordEvent(entity, actorId, action, state, "no_sensitive_fields", now);
    sessionRepository.save(entity);
    appendAudit(entity, actorId, "SECURE_DEBUG_" + action, state, "no_sensitive_fields");
  }

  private String recordEvent(
      SecureDebugSessionEntity entity,
      String actorId,
      String action,
      String result,
      String projection,
      Instant now) {
    var sequence = entity.nextEventSequence();
    var previous = entity.getEvidenceHeadHash();
    var hash =
        PromptSecurityService.sha256(
            entity.getDebugSessionId()
                + "|"
                + sequence
                + "|"
                + nullToEmpty(previous)
                + "|"
                + actorId
                + "|"
                + action
                + "|"
                + result
                + "|"
                + projection
                + "|"
                + now);
    accessEventRepository.save(
        new SecureDebugAccessEventEntity(
            identifier("sda_"),
            entity.getDebugSessionId(),
            entity.getTenantId(),
            sequence,
            actorId,
            action,
            result,
            projection,
            previous,
            hash,
            now));
    entity.advanceEvidence(hash);
    return hash;
  }

  private void appendAudit(
      SecureDebugSessionEntity entity,
      String actorId,
      String action,
      String result,
      String projection) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("debugSessionId", entity.getDebugSessionId());
    details.put("breakGlassRequestId", entity.getBreakGlassRequestId());
    details.put("fieldProjection", projection);
    details.put("accessCount", entity.getAccessCount());
    details.put("expiresAt", entity.getExpiresAt().toString());
    details.put("evidenceHeadHash", entity.getEvidenceHeadHash());
    auditService.append(
        new AuditApplicationService.AuditRecord(
            entity.getTenantId(),
            entity.getResourceId(),
            "ADMIN_ACCESS",
            "system".equals(actorId) ? "SYSTEM" : "USER",
            actorId,
            entity.getResourceType(),
            entity.getResourceId(),
            action,
            result,
            details,
            entity.getDebugSessionId()));
  }

  private static SecureDebugSessionView toView(SecureDebugSessionEntity entity) {
    return new SecureDebugSessionView(
        entity.getDebugSessionId(),
        entity.getBreakGlassRequestId(),
        entity.getResourceType(),
        entity.getResourceId(),
        entity.getOperatorId(),
        entity.getState(),
        entity.getStartedAt(),
        entity.getExpiresAt(),
        entity.getEndedAt(),
        entity.getEndReason(),
        entity.getAccessCount(),
        entity.getLastAccessAt(),
        entity.getEvidenceHeadHash());
  }

  private static String origin(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      return null;
    }
    try {
      var uri = new URI(rawUrl);
      if (uri.getScheme() == null) {
        return "REDACTED";
      }
      if (uri.getHost() == null) {
        return uri.getScheme() + ":";
      }
      return uri.getScheme()
          + "://"
          + uri.getHost()
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    } catch (URISyntaxException exception) {
      return "REDACTED";
    }
  }

  private static Instant minimum(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  private static String identifier(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public static final class SecureDebugNotFoundException extends RuntimeException {}

  public static final class SecureDebugRejectedException extends RuntimeException {
    public SecureDebugRejectedException(String reason) {
      super(reason);
    }
  }
}
