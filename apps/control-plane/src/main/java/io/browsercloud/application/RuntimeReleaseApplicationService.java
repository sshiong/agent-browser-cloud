package io.browsercloud.application;

import io.browsercloud.api.RuntimeReleaseRequestListResponse;
import io.browsercloud.api.RuntimeReleaseRequestView;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import io.browsercloud.persistence.RuntimeReleaseRequestEntity;
import io.browsercloud.persistence.RuntimeReleaseRequestJpaRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-level, dual-control Runtime promotion and emergency disable governance. */
@Service
public class RuntimeReleaseApplicationService {

  private final RuntimeReleaseRequestJpaRepository releaseRepository;
  private final RuntimeBuildJpaRepository buildRepository;
  private final RuntimeBuildPolicy buildPolicy;
  private final AuditApplicationService auditService;

  public RuntimeReleaseApplicationService(
      RuntimeReleaseRequestJpaRepository releaseRepository,
      RuntimeBuildJpaRepository buildRepository,
      RuntimeBuildPolicy buildPolicy,
      AuditApplicationService auditService) {
    this.releaseRepository = releaseRepository;
    this.buildRepository = buildRepository;
    this.buildPolicy = buildPolicy;
    this.auditService = auditService;
  }

  @Transactional
  public RuntimeReleaseRequestView requestPromotion(
      String tenantId, String actorId, String buildId, String targetChannel, String reason) {
    buildPolicy.requireReleaseCandidate(buildId);
    return create(tenantId, actorId, buildId, targetChannel, reason);
  }

  @Transactional
  public RuntimeReleaseRequestView requestDisable(
      String tenantId, String actorId, String buildId, String reason) {
    var build = buildRepository.findById(buildId).orElseThrow(RuntimeReleaseNotFoundException::new);
    if ("DISABLED".equals(build.getReleaseChannel())) {
      throw new RuntimeReleaseRejectedException("BUILD_ALREADY_DISABLED");
    }
    return create(tenantId, actorId, buildId, "DISABLED", reason);
  }

  @Transactional(readOnly = true)
  public RuntimeReleaseRequestListResponse list(String tenantId) {
    var items =
        releaseRepository.findAllByTenantIdOrderByRequestedAtDesc(tenantId).stream()
            .map(RuntimeReleaseApplicationService::toView)
            .toList();
    return new RuntimeReleaseRequestListResponse(items, items.size());
  }

  @Transactional
  public RuntimeReleaseRequestView approve(String releaseId, String tenantId, String actorId) {
    var request = requireForUpdate(releaseId, tenantId);
    if ("APPROVED".equals(request.getState())) {
      return toView(request);
    }
    requireRequested(request);
    if (request.getRequestedBy().equals(actorId)) {
      auditService.appendIndependent(
          auditRecord(request, actorId, "RUNTIME_RELEASE_APPROVAL_DENIED", "SEPARATION_OF_DUTIES"));
      throw new RuntimeReleaseRejectedException("REQUESTER_CANNOT_APPROVE");
    }
    var build =
        buildRepository
            .findForUpdate(request.getBuildId())
            .orElseThrow(RuntimeReleaseNotFoundException::new);
    if (!"DISABLED".equals(request.getTargetChannel())) {
      buildPolicy.requireReleaseCandidate(build.getBuildId());
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var evidenceHash = evidenceHash(request, actorId);
    if ("DISABLED".equals(request.getTargetChannel())) {
      build.disable(actorId, now);
    } else {
      build.release(request.getTargetChannel(), now);
    }
    request.approve(actorId, evidenceHash, now);
    buildRepository.save(build);
    releaseRepository.save(request);
    appendAudit(request, actorId, "RUNTIME_RELEASE_APPROVED", request.getTargetChannel());
    return toView(request);
  }

  @Transactional
  public RuntimeReleaseRequestView reject(String releaseId, String tenantId, String actorId) {
    var request = requireForUpdate(releaseId, tenantId);
    if ("REJECTED".equals(request.getState())) {
      return toView(request);
    }
    requireRequested(request);
    request.reject(actorId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    releaseRepository.save(request);
    appendAudit(request, actorId, "RUNTIME_RELEASE_REJECTED", "REJECTED");
    return toView(request);
  }

  private RuntimeReleaseRequestView create(
      String tenantId, String actorId, String buildId, String targetChannel, String reason) {
    if (releaseRepository.existsByBuildIdAndTargetChannelAndState(
        buildId, targetChannel, "REQUESTED")) {
      throw new RuntimeReleaseRejectedException("PENDING_DECISION_ALREADY_EXISTS");
    }
    var entity =
        new RuntimeReleaseRequestEntity(
            "rel_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
            tenantId,
            buildId,
            targetChannel,
            AgentDataMinimizer.redact(reason),
            actorId,
            Instant.now().truncatedTo(ChronoUnit.MICROS));
    try {
      releaseRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      throw new RuntimeReleaseRejectedException("PENDING_DECISION_ALREADY_EXISTS");
    }
    appendAudit(entity, actorId, "RUNTIME_RELEASE_REQUESTED", "PENDING");
    return toView(entity);
  }

  private RuntimeReleaseRequestEntity requireForUpdate(String releaseId, String tenantId) {
    return releaseRepository
        .findForUpdate(releaseId, tenantId)
        .orElseThrow(RuntimeReleaseNotFoundException::new);
  }

  private static void requireRequested(RuntimeReleaseRequestEntity request) {
    if (!"REQUESTED".equals(request.getState())) {
      throw new RuntimeReleaseRejectedException("INVALID_STATE_" + request.getState());
    }
  }

  private void appendAudit(
      RuntimeReleaseRequestEntity request, String actorId, String action, String result) {
    auditService.append(auditRecord(request, actorId, action, result));
  }

  private static AuditApplicationService.AuditRecord auditRecord(
      RuntimeReleaseRequestEntity request, String actorId, String action, String result) {
    return new AuditApplicationService.AuditRecord(
        request.getTenantId(),
        null,
        "RUNTIME_RELEASE",
        "USER",
        actorId,
        "RUNTIME_BUILD",
        request.getBuildId(),
        action,
        result,
        Map.of(
            "releaseId", request.getReleaseId(),
            "targetChannel", request.getTargetChannel(),
            "requestedBy", request.getRequestedBy()),
        request.getReleaseId());
  }

  private static String evidenceHash(RuntimeReleaseRequestEntity request, String actorId) {
    return PromptSecurityService.sha256(
        request.getReleaseId()
            + "|"
            + request.getTenantId()
            + "|"
            + request.getBuildId()
            + "|"
            + request.getTargetChannel()
            + "|"
            + request.getRequestedBy()
            + "|"
            + actorId
            + "|"
            + request.getRequestedAt());
  }

  private static RuntimeReleaseRequestView toView(RuntimeReleaseRequestEntity request) {
    return new RuntimeReleaseRequestView(
        request.getReleaseId(),
        request.getBuildId(),
        request.getTargetChannel(),
        request.getReason(),
        request.getState(),
        request.getRequestedBy(),
        request.getApprovedBy(),
        request.getRejectedBy(),
        request.getRequestedAt(),
        request.getDecidedAt(),
        request.getEvidenceHash());
  }

  public static final class RuntimeReleaseNotFoundException extends RuntimeException {}

  public static final class RuntimeReleaseRejectedException extends RuntimeException {
    public RuntimeReleaseRejectedException(String reason) {
      super(reason);
    }
  }
}
