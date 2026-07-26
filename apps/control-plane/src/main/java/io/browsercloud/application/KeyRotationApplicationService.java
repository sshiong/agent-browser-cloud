package io.browsercloud.application;

import io.browsercloud.api.CompleteKeyRotationRequest;
import io.browsercloud.api.CreateKeyRotationRequest;
import io.browsercloud.api.KeyRotationRequestListResponse;
import io.browsercloud.api.KeyRotationRequestView;
import io.browsercloud.persistence.KeyRotationRequestEntity;
import io.browsercloud.persistence.KeyRotationRequestJpaRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dual-control rotation lifecycle for platform and tenant cryptographic keys. */
@Service
public class KeyRotationApplicationService {

  private static final List<String> ACTIVE_STATES = List.of("REQUESTED", "ROTATING");

  private final KeyRotationRequestJpaRepository repository;
  private final AuditApplicationService auditService;

  public KeyRotationApplicationService(
      KeyRotationRequestJpaRepository repository, AuditApplicationService auditService) {
    this.repository = repository;
    this.auditService = auditService;
  }

  @Transactional
  public KeyRotationRequestView request(
      String tenantId, String actorId, CreateKeyRotationRequest input) {
    if (input.oldKeyId().equals(input.newKeyId())) {
      throw new KeyRotationRejectedException("OLD_AND_NEW_KEY_MUST_DIFFER");
    }
    if (repository.existsByKeyScopeAndOldKeyIdAndStateIn(
        input.keyScope(), input.oldKeyId(), ACTIVE_STATES)) {
      throw new KeyRotationRejectedException("ACTIVE_ROTATION_ALREADY_EXISTS");
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var entity =
        new KeyRotationRequestEntity(
            "rot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
            tenantId,
            input.keyScope(),
            input.oldKeyId(),
            input.newKeyId(),
            input.rotationTrigger(),
            AgentDataMinimizer.redact(input.reason()),
            input.overlapMinutes(),
            actorId,
            now);
    try {
      repository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      throw new KeyRotationRejectedException("ACTIVE_ROTATION_ALREADY_EXISTS");
    }
    appendAudit(entity, actorId, "KEY_ROTATION_REQUESTED", "PENDING");
    return toView(entity);
  }

  @Transactional(readOnly = true)
  public KeyRotationRequestListResponse list(String tenantId) {
    var items =
        repository.findAllByTenantIdOrderByRequestedAtDesc(tenantId).stream()
            .map(KeyRotationApplicationService::toView)
            .toList();
    return new KeyRotationRequestListResponse(items, items.size());
  }

  @Transactional
  public KeyRotationRequestView approve(String rotationId, String tenantId, String actorId) {
    var entity = requireForUpdate(rotationId, tenantId);
    if ("ROTATING".equals(entity.getState())) {
      return toView(entity);
    }
    requireState(entity, "REQUESTED");
    if (entity.getRequestedBy().equals(actorId)) {
      auditService.appendIndependent(
          auditRecord(entity, actorId, "KEY_ROTATION_APPROVAL_DENIED", "SEPARATION_OF_DUTIES"));
      throw new KeyRotationRejectedException("REQUESTER_CANNOT_APPROVE");
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var overlapMinutes =
        "SUSPECTED_COMPROMISE".equals(entity.getRotationTrigger())
            ? 0
            : entity.getRequestedOverlapMinutes();
    var overlapUntil = now.plus(overlapMinutes, ChronoUnit.MINUTES);
    entity.approve(actorId, now, overlapUntil, approvalEvidenceHash(entity, actorId));
    repository.save(entity);
    appendAudit(entity, actorId, "KEY_ROTATION_APPROVED", "ROTATING");
    return toView(entity);
  }

  @Transactional
  public KeyRotationRequestView complete(
      String rotationId, String tenantId, String actorId, CompleteKeyRotationRequest input) {
    var entity = requireForUpdate(rotationId, tenantId);
    if ("COMPLETED".equals(entity.getState())) {
      return toView(entity);
    }
    requireState(entity, "ROTATING");
    var compromise = "SUSPECTED_COMPROMISE".equals(entity.getRotationTrigger());
    if (!input.newKeyWriteVerified()
        || !input.plaintextRejected()
        || (!compromise && !input.oldKeyReadVerified())) {
      auditService.appendIndependent(
          auditRecord(entity, actorId, "KEY_ROTATION_COMPLETION_DENIED", "VERIFICATION_FAILED"));
      throw new KeyRotationRejectedException("ROTATION_VERIFICATION_FAILED");
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    if (!compromise && entity.getOverlapUntil().isAfter(now)) {
      auditService.appendIndependent(
          auditRecord(entity, actorId, "KEY_ROTATION_COMPLETION_DENIED", "OVERLAP_NOT_ELAPSED"));
      throw new KeyRotationRejectedException("VERIFIER_OVERLAP_NOT_ELAPSED");
    }
    var reference = AgentDataMinimizer.redact(input.verificationReference());
    var evidenceHash = completionEvidenceHash(entity, actorId, input, reference);
    entity.complete(
        actorId,
        now,
        input.newKeyWriteVerified(),
        input.oldKeyReadVerified(),
        input.plaintextRejected(),
        input.affectedWorkloads(),
        reference,
        evidenceHash);
    repository.save(entity);
    appendAudit(entity, actorId, "KEY_ROTATION_COMPLETED", "COMPLETED");
    return toView(entity);
  }

  @Transactional
  public KeyRotationRequestView revoke(String rotationId, String tenantId, String actorId) {
    var entity = requireForUpdate(rotationId, tenantId);
    if ("REVOKED".equals(entity.getState())) {
      return toView(entity);
    }
    if (!ACTIVE_STATES.contains(entity.getState())) {
      throw new KeyRotationRejectedException("INVALID_STATE_" + entity.getState());
    }
    entity.revoke(actorId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    repository.save(entity);
    appendAudit(entity, actorId, "KEY_ROTATION_REVOKED", "REVOKED");
    return toView(entity);
  }

  private KeyRotationRequestEntity requireForUpdate(String rotationId, String tenantId) {
    return repository
        .findForUpdate(rotationId, tenantId)
        .orElseThrow(KeyRotationNotFoundException::new);
  }

  private static void requireState(KeyRotationRequestEntity entity, String state) {
    if (!state.equals(entity.getState())) {
      throw new KeyRotationRejectedException("INVALID_STATE_" + entity.getState());
    }
  }

  private void appendAudit(
      KeyRotationRequestEntity entity, String actorId, String action, String result) {
    auditService.append(auditRecord(entity, actorId, action, result));
  }

  private static AuditApplicationService.AuditRecord auditRecord(
      KeyRotationRequestEntity entity, String actorId, String action, String result) {
    return new AuditApplicationService.AuditRecord(
        entity.getTenantId(),
        null,
        "KEY_ROTATION",
        "USER",
        actorId,
        "CRYPTOGRAPHIC_KEY",
        entity.getKeyScope(),
        action,
        result,
        Map.of(
            "rotationId", entity.getRotationId(),
            "keyScope", entity.getKeyScope(),
            "oldKeyId", entity.getOldKeyId(),
            "newKeyId", entity.getNewKeyId(),
            "trigger", entity.getRotationTrigger()),
        entity.getRotationId());
  }

  private static String approvalEvidenceHash(KeyRotationRequestEntity entity, String actorId) {
    return PromptSecurityService.sha256(
        entity.getRotationId()
            + "|"
            + entity.getTenantId()
            + "|"
            + entity.getKeyScope()
            + "|"
            + entity.getOldKeyId()
            + "|"
            + entity.getNewKeyId()
            + "|"
            + entity.getRotationTrigger()
            + "|"
            + entity.getRequestedBy()
            + "|"
            + actorId
            + "|"
            + entity.getRequestedAt());
  }

  private static String completionEvidenceHash(
      KeyRotationRequestEntity entity,
      String actorId,
      CompleteKeyRotationRequest input,
      String reference) {
    return PromptSecurityService.sha256(
        entity.getApprovalEvidenceHash()
            + "|"
            + actorId
            + "|"
            + input.newKeyWriteVerified()
            + "|"
            + input.oldKeyReadVerified()
            + "|"
            + input.plaintextRejected()
            + "|"
            + input.affectedWorkloads()
            + "|"
            + reference);
  }

  private static KeyRotationRequestView toView(KeyRotationRequestEntity entity) {
    return new KeyRotationRequestView(
        entity.getRotationId(),
        entity.getKeyScope(),
        entity.getOldKeyId(),
        entity.getNewKeyId(),
        entity.getRotationTrigger(),
        entity.getReason(),
        entity.getRequestedOverlapMinutes(),
        entity.getState(),
        entity.getRequestedBy(),
        entity.getApprovedBy(),
        entity.getCompletedBy(),
        entity.getRevokedBy(),
        entity.getRequestedAt(),
        entity.getApprovedAt(),
        entity.getStartedAt(),
        entity.getCompletedAt(),
        entity.getRevokedAt(),
        entity.getOverlapUntil(),
        entity.getProgressPercent(),
        entity.getNewKeyWriteVerified(),
        entity.getOldKeyReadVerified(),
        entity.getPlaintextRejected(),
        entity.getAffectedWorkloads(),
        entity.getVerificationReference(),
        entity.getApprovalEvidenceHash(),
        entity.getCompletionEvidenceHash());
  }

  public static final class KeyRotationNotFoundException extends RuntimeException {}

  public static final class KeyRotationRejectedException extends RuntimeException {
    public KeyRotationRejectedException(String reason) {
      super(reason);
    }
  }
}
