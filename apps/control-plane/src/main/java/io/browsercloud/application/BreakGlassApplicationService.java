package io.browsercloud.application;

import io.browsercloud.api.BreakGlassRequestListResponse;
import io.browsercloud.api.BreakGlassRequestView;
import io.browsercloud.api.CreateBreakGlassRequest;
import io.browsercloud.persistence.BreakGlassRequestEntity;
import io.browsercloud.persistence.BreakGlassRequestJpaRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 双人审批、限时和自动撤销的生产紧急访问治理。 */
@Service
public class BreakGlassApplicationService {

  private final BreakGlassRequestJpaRepository repository;
  private final AuditApplicationService auditService;

  public BreakGlassApplicationService(
      BreakGlassRequestJpaRepository repository, AuditApplicationService auditService) {
    this.repository = repository;
    this.auditService = auditService;
  }

  @Transactional
  public BreakGlassRequestView request(
      String tenantId, String actorId, CreateBreakGlassRequest request) {
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var requestId = "bgr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var entity =
        new BreakGlassRequestEntity(
            requestId,
            tenantId,
            request.ticketId(),
            AgentDataMinimizer.redact(request.reason()),
            request.resourceType(),
            request.resourceId(),
            request.requestedScope(),
            actorId,
            now,
            now.plus(request.durationMinutes(), ChronoUnit.MINUTES));
    repository.save(entity);
    appendAudit(entity, actorId, "BREAK_GLASS_REQUESTED", "PENDING");
    return toView(entity);
  }

  @Transactional(readOnly = true)
  public BreakGlassRequestListResponse list(String tenantId) {
    var items =
        repository.findAllByTenantIdOrderByRequestedAtDesc(tenantId).stream()
            .map(BreakGlassApplicationService::toView)
            .toList();
    return new BreakGlassRequestListResponse(items, items.size());
  }

  @Transactional(noRollbackFor = BreakGlassRejectedException.class)
  public BreakGlassRequestView approve(String requestId, String tenantId, String actorId) {
    var entity = requireForUpdate(requestId, tenantId);
    if ("ACTIVE".equals(entity.getState())) {
      return toView(entity);
    }
    requireState(entity, "REQUESTED");
    if (entity.getRequestedBy().equals(actorId)) {
      appendIndependentAudit(
          entity, actorId, "BREAK_GLASS_APPROVAL_DENIED", "SEPARATION_OF_DUTIES");
      throw new BreakGlassRejectedException("REQUESTER_CANNOT_APPROVE");
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    if (!entity.getExpiresAt().isAfter(now)) {
      entity.expire(now);
      repository.save(entity);
      appendAudit(entity, "system", "BREAK_GLASS_AUTO_REVOKED", "EXPIRED");
      throw new BreakGlassRejectedException("REQUEST_EXPIRED");
    }
    entity.approve(actorId, evidenceHash(entity, actorId), now);
    repository.save(entity);
    appendAudit(entity, actorId, "BREAK_GLASS_APPROVED", "ACTIVE");
    return toView(entity);
  }

  @Transactional
  public BreakGlassRequestView reject(String requestId, String tenantId, String actorId) {
    var entity = requireForUpdate(requestId, tenantId);
    if ("REJECTED".equals(entity.getState())) {
      return toView(entity);
    }
    requireState(entity, "REQUESTED");
    entity.reject(actorId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    repository.save(entity);
    appendAudit(entity, actorId, "BREAK_GLASS_REJECTED", "REJECTED");
    return toView(entity);
  }

  @Transactional
  public BreakGlassRequestView revoke(String requestId, String tenantId, String actorId) {
    var entity = requireForUpdate(requestId, tenantId);
    if ("REVOKED".equals(entity.getState()) || "EXPIRED".equals(entity.getState())) {
      return toView(entity);
    }
    requireState(entity, "ACTIVE");
    entity.revoke(actorId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    repository.save(entity);
    appendAudit(entity, actorId, "BREAK_GLASS_REVOKED", "REVOKED");
    return toView(entity);
  }

  @Transactional
  public BreakGlassRequestView review(String requestId, String tenantId, String actorId) {
    var entity = requireForUpdate(requestId, tenantId);
    if ("REQUESTED".equals(entity.getState()) || "ACTIVE".equals(entity.getState())) {
      throw new BreakGlassRejectedException("ACTIVE_REQUEST_CANNOT_BE_REVIEWED");
    }
    if (entity.getReviewedAt() == null) {
      entity.markReviewed(Instant.now().truncatedTo(ChronoUnit.MICROS));
      repository.save(entity);
      appendAudit(entity, actorId, "BREAK_GLASS_REVIEWED", "COMPLETED");
    }
    return toView(entity);
  }

  @Transactional
  public boolean authorize(
      String requestId,
      String tenantId,
      String actorId,
      String resourceType,
      String resourceId,
      String requestedScope) {
    var entity = requireForUpdate(requestId, tenantId);
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    if ("ACTIVE".equals(entity.getState()) && !entity.getExpiresAt().isAfter(now)) {
      entity.expire(now);
      repository.save(entity);
      appendAudit(entity, "system", "BREAK_GLASS_AUTO_REVOKED", "EXPIRED");
    }
    var authorized =
        "ACTIVE".equals(entity.getState())
            && entity.getRequestedBy().equals(actorId)
            && entity.getResourceType().equals(resourceType)
            && entity.getResourceId().equals(resourceId)
            && entity.getRequestedScope().equals(requestedScope);
    appendAudit(entity, actorId, "BREAK_GLASS_ACCESS_CHECK", authorized ? "AUTHORIZED" : "DENIED");
    return authorized;
  }

  @Scheduled(fixedDelayString = "${security.break-glass-scan-interval-ms:30000}")
  @Transactional
  public void expireActiveGrants() {
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    for (var entity : repository.findExpiredActiveForUpdate(now)) {
      entity.expire(now);
      repository.save(entity);
      appendAudit(entity, "system", "BREAK_GLASS_AUTO_REVOKED", "EXPIRED");
    }
  }

  private BreakGlassRequestEntity requireForUpdate(String requestId, String tenantId) {
    return repository
        .findForUpdate(requestId, tenantId)
        .orElseThrow(BreakGlassNotFoundException::new);
  }

  private static void requireState(BreakGlassRequestEntity entity, String expected) {
    if (!expected.equals(entity.getState())) {
      throw new BreakGlassRejectedException("INVALID_STATE_" + entity.getState());
    }
  }

  private static String evidenceHash(BreakGlassRequestEntity entity, String approver) {
    return PromptSecurityService.sha256(
        entity.getRequestId()
            + "|"
            + entity.getTenantId()
            + "|"
            + entity.getTicketId()
            + "|"
            + entity.getResourceType()
            + "|"
            + entity.getResourceId()
            + "|"
            + entity.getRequestedScope()
            + "|"
            + entity.getRequestedBy()
            + "|"
            + approver
            + "|"
            + entity.getExpiresAt());
  }

  private void appendAudit(
      BreakGlassRequestEntity entity, String actorId, String action, String result) {
    auditService.append(auditRecord(entity, actorId, action, result));
  }

  private void appendIndependentAudit(
      BreakGlassRequestEntity entity, String actorId, String action, String result) {
    auditService.appendIndependent(auditRecord(entity, actorId, action, result));
  }

  private static AuditApplicationService.AuditRecord auditRecord(
      BreakGlassRequestEntity entity, String actorId, String action, String result) {
    return new AuditApplicationService.AuditRecord(
        entity.getTenantId(),
        null,
        "ADMIN_ACCESS",
        "system".equals(actorId) ? "SYSTEM" : "USER",
        actorId,
        entity.getResourceType(),
        entity.getResourceId(),
        action,
        result,
        Map.of(
            "breakGlassRequestId",
            entity.getRequestId(),
            "ticketId",
            entity.getTicketId(),
            "scope",
            entity.getRequestedScope(),
            "expiresAt",
            entity.getExpiresAt().toString()),
        entity.getRequestId());
  }

  private static BreakGlassRequestView toView(BreakGlassRequestEntity entity) {
    return new BreakGlassRequestView(
        entity.getRequestId(),
        entity.getTicketId(),
        entity.getReason(),
        entity.getResourceType(),
        entity.getResourceId(),
        entity.getRequestedScope(),
        entity.getState(),
        entity.getRequestedBy(),
        entity.getApprovedBy(),
        entity.getRejectedBy(),
        entity.getRevokedBy(),
        entity.getEvidenceHash(),
        entity.getRequestedAt(),
        entity.getApprovedAt(),
        entity.getRejectedAt(),
        entity.getRevokedAt(),
        entity.getExpiresAt(),
        entity.getReviewedAt());
  }

  public static final class BreakGlassNotFoundException extends RuntimeException {}

  public static final class BreakGlassRejectedException extends RuntimeException {
    public BreakGlassRejectedException(String reason) {
      super(reason);
    }
  }
}
