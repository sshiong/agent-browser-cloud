package io.browsercloud.application;

import static io.browsercloud.api.SessionEvidenceModels.*;
import static io.browsercloud.application.SessionEvidenceAccessNodeGateway.*;

import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.session.SessionState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Privacy-governed Observer evidence workflow. Capture is asynchronous through the transactional
 * outbox; raw pixels are accessible only through same-actor, purpose-bound, one-time grants.
 */
@Service
public class SessionEvidenceGovernanceService {

  private static final Duration GRANT_LIFETIME = Duration.ofMinutes(5);
  private static final int SIGNED_URL_SECONDS = 60;

  private final SessionRepository sessions;
  private final OperationRepository operations;
  private final BrowserCapacityApplicationService capacity;
  private final NodeCommandGateway commands;
  private final SessionEvidenceGovernanceStore store;
  private final SessionEvidenceAccessNodeGateway nodeAccess;
  private final AuditApplicationService audit;

  public SessionEvidenceGovernanceService(
      SessionRepository sessions,
      OperationRepository operations,
      BrowserCapacityApplicationService capacity,
      NodeCommandGateway commands,
      SessionEvidenceGovernanceStore store,
      SessionEvidenceAccessNodeGateway nodeAccess,
      AuditApplicationService audit) {
    this.sessions = sessions;
    this.operations = operations;
    this.capacity = capacity;
    this.commands = commands;
    this.store = store;
    this.nodeAccess = nodeAccess;
    this.audit = audit;
  }

  @Transactional
  public EvidenceCaptureView capture(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CaptureEvidenceRequest request) {
    if (request.purpose() == EvidencePurpose.AGENT_PERCEPTION) {
      throw new EvidenceGovernanceRejectedException("AGENT_PERCEPTION_PURPOSE_RESERVED");
    }
    var session = sessions.requireForUpdate(sessionId);
    requireTenant(session.tenantId(), tenantId, sessionId);
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new EvidenceGovernanceRejectedException("SESSION_NOT_RUNNING");
    }
    if (session.nodeId() == null
        || !capacity.nodeHasCapability(session.nodeId(), "observerEvidence", "cdp-s3-v1")) {
      throw new EvidenceGovernanceRejectedException("OBSERVER_EVIDENCE_UNAVAILABLE");
    }
    if (operations
        .findActive(sessionId)
        .filter(operation -> operation.mode() == OperationMode.HUMAN_TAKEOVER)
        .isPresent()) {
      throw new EvidenceGovernanceRejectedException("HUMAN_TAKEOVER_ACTIVE");
    }

    var existing = store.findCaptureByIdempotency(tenantId, actorId, idempotencyKey);
    if (existing.isPresent()) {
      requireSameCapture(existing.orElseThrow(), sessionId, request.purpose());
      return existing.orElseThrow();
    }

    var now = Instant.now();
    var captureId = newId("cap_");
    var commandId = newId("cmd_");
    var inserted =
        store.insertCapture(
            captureId,
            tenantId,
            sessionId,
            actorId,
            request.purpose(),
            idempotencyKey,
            commandId,
            requestId,
            now);
    if (!inserted) {
      var raced =
          store
              .findCaptureByIdempotency(tenantId, actorId, idempotencyKey)
              .orElseThrow(
                  () ->
                      new EvidenceGovernanceRejectedException(
                          "EVIDENCE_CAPTURE_IDEMPOTENCY_CONFLICT"));
      requireSameCapture(raced, sessionId, request.purpose());
      return raced;
    }
    commands.send(NodeCommands.captureObserverScreenshot(session, captureId, commandId));
    audit.append(
        auditRecord(
            tenantId,
            sessionId,
            actorId,
            captureId,
            "OBSERVER_EVIDENCE_CAPTURE_REQUESTED",
            "ACCEPTED",
            request.purpose(),
            requestId));
    return store
        .findCapture(tenantId, sessionId, captureId)
        .orElseThrow(
            () -> new EvidenceGovernanceRejectedException("EVIDENCE_CAPTURE_STATE_UNAVAILABLE"));
  }

  @Transactional(readOnly = true)
  public EvidenceCaptureView getCapture(String sessionId, String captureId, String tenantId) {
    requireTenant(sessions.require(sessionId).tenantId(), tenantId, sessionId);
    return store
        .findCapture(tenantId, sessionId, captureId)
        .orElseThrow(() -> new EvidenceGovernanceNotFoundException("EVIDENCE_CAPTURE_NOT_FOUND"));
  }

  @Transactional
  public EvidenceAccessGrantView createAccessGrant(
      String sessionId,
      String evidenceId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CreateEvidenceAccessGrantRequest request) {
    if (request.purpose() == EvidencePurpose.AGENT_PERCEPTION) {
      throw new EvidenceGovernanceRejectedException("AGENT_PERCEPTION_PURPOSE_RESERVED");
    }
    var session = sessions.requireForUpdate(sessionId);
    requireTenant(session.tenantId(), tenantId, sessionId);
    store
        .findCommittedEvidence(tenantId, sessionId, evidenceId)
        .orElseThrow(() -> new EvidenceGovernanceNotFoundException("EVIDENCE_NOT_FOUND"));

    var existing = store.findGrantByIdempotency(tenantId, actorId, idempotencyKey);
    if (existing.isPresent()) {
      requireSameGrant(existing.orElseThrow(), sessionId, evidenceId, request.purpose());
      return existing.orElseThrow();
    }
    var now = Instant.now();
    var grantId = newId("egr_");
    var inserted =
        store.insertGrant(
            grantId,
            tenantId,
            sessionId,
            evidenceId,
            actorId,
            request.purpose(),
            idempotencyKey,
            requestId,
            now.plus(GRANT_LIFETIME),
            now);
    if (!inserted) {
      var raced =
          store
              .findGrantByIdempotency(tenantId, actorId, idempotencyKey)
              .orElseThrow(
                  () ->
                      new EvidenceGovernanceRejectedException(
                          "EVIDENCE_ACCESS_IDEMPOTENCY_CONFLICT"));
      requireSameGrant(raced, sessionId, evidenceId, request.purpose());
      return raced;
    }
    audit.append(
        auditRecord(
            tenantId,
            sessionId,
            actorId,
            grantId,
            "EVIDENCE_ACCESS_GRANTED",
            "COMMITTED",
            request.purpose(),
            requestId));
    return store
        .findGrantByIdempotency(tenantId, actorId, idempotencyKey)
        .orElseThrow(
            () -> new EvidenceGovernanceRejectedException("EVIDENCE_ACCESS_STATE_UNAVAILABLE"));
  }

  public RedeemEvidenceAccessResponse redeem(
      String sessionId, String grantId, String tenantId, String actorId, String requestId) {
    return redeem(sessionId, grantId, tenantId, actorId, requestId, null);
  }

  public RedeemEvidenceAccessResponse redeem(
      String sessionId,
      String grantId,
      String tenantId,
      String actorId,
      String requestId,
      EvidencePurpose expectedPurpose) {
    requireTenant(sessions.require(sessionId).tenantId(), tenantId, sessionId);
    var claim = store.claim(tenantId, sessionId, grantId, actorId, expectedPurpose, Instant.now());
    if (!capacity.nodeHasCapability(claim.nodeId(), "evidenceAccess", "presigned-get-v1")) {
      store.failGrant(grantId, "EVIDENCE_ACCESS_NODE_UNAVAILABLE", Instant.now());
      appendRedeemAudit(
          tenantId, sessionId, actorId, grantId, "FAILED", requestId, "NODE_CAPABILITY_MISSING");
      throw new EvidenceGovernanceRejectedException("EVIDENCE_ACCESS_NODE_UNAVAILABLE");
    }
    SignedEvidenceAccess signed;
    try {
      signed =
          nodeAccess.sign(
              new SignEvidenceAccessRequest(
                  grantId,
                  claim.nodeId(),
                  tenantId,
                  claim.profileId(),
                  sessionId,
                  claim.evidenceId(),
                  claim.contentSha256(),
                  claim.contentBytes(),
                  SIGNED_URL_SECONDS));
    } catch (RuntimeException exception) {
      store.failGrant(grantId, safeFailureCode(exception), Instant.now());
      appendRedeemAudit(
          tenantId, sessionId, actorId, grantId, "FAILED", requestId, safeFailureCode(exception));
      throw exception;
    }
    store.commitGrant(grantId, signed.nodeId(), Instant.now());
    appendRedeemAudit(
        tenantId, sessionId, actorId, grantId, "COMMITTED", requestId, "ONE_TIME_REDEEMED");
    return new RedeemEvidenceAccessResponse(
        signed.grantId(), signed.evidenceId(), signed.downloadUrl(), signed.expiresAt());
  }

  private void appendRedeemAudit(
      String tenantId,
      String sessionId,
      String actorId,
      String grantId,
      String result,
      String requestId,
      String reason) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "SESSION_EVIDENCE",
            "USER",
            actorId,
            "EVIDENCE_ACCESS_GRANT",
            grantId,
            "EVIDENCE_ACCESS_REDEEMED",
            result,
            Map.of("reason", reason),
            requestId));
  }

  private static AuditApplicationService.AuditRecord auditRecord(
      String tenantId,
      String sessionId,
      String actorId,
      String resourceId,
      String action,
      String result,
      EvidencePurpose purpose,
      String requestId) {
    return new AuditApplicationService.AuditRecord(
        tenantId,
        sessionId,
        "SESSION_EVIDENCE",
        "USER",
        actorId,
        "SESSION_EVIDENCE",
        resourceId,
        action,
        result,
        Map.of("purpose", purpose.name()),
        requestId);
  }

  private static String safeFailureCode(RuntimeException exception) {
    if (exception instanceof EvidenceAccessNodeRejectedException) {
      return "EVIDENCE_ACCESS_NODE_REJECTED";
    }
    return "EVIDENCE_ACCESS_NODE_FAILED";
  }

  private static void requireSameCapture(
      EvidenceCaptureView existing, String sessionId, EvidencePurpose purpose) {
    if (!existing.sessionId().equals(sessionId) || existing.purpose() != purpose) {
      throw new EvidenceGovernanceRejectedException("EVIDENCE_CAPTURE_IDEMPOTENCY_CONFLICT");
    }
  }

  private static void requireSameGrant(
      EvidenceAccessGrantView existing,
      String sessionId,
      String evidenceId,
      EvidencePurpose purpose) {
    if (!existing.sessionId().equals(sessionId)
        || !existing.evidenceId().equals(evidenceId)
        || existing.purpose() != purpose) {
      throw new EvidenceGovernanceRejectedException("EVIDENCE_ACCESS_IDEMPOTENCY_CONFLICT");
    }
  }

  private static void requireTenant(
      String authoritativeTenantId, String tenantId, String sessionId) {
    if (!authoritativeTenantId.equals(tenantId)) {
      throw new SessionNotFoundException(sessionId);
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class EvidenceGovernanceNotFoundException extends RuntimeException {
    public EvidenceGovernanceNotFoundException(String message) {
      super(message);
    }
  }

  public static final class EvidenceGovernanceRejectedException extends RuntimeException {
    public EvidenceGovernanceRejectedException(String message) {
      super(message);
    }
  }
}
