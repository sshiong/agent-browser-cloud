package io.browsercloud.application;

import static io.browsercloud.api.SessionRecordingModels.*;
import static io.browsercloud.application.SessionRecordingPlaybackNodeGateway.*;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Issues and redeems purpose-bound, actor-bound, one-time recording playback grants. */
@Service
public class SessionRecordingPlaybackApplicationService {

  private static final Duration GRANT_LIFETIME = Duration.ofMinutes(5);
  private static final Duration PLAYBACK_LIFETIME = Duration.ofMinutes(5);
  private static final int SIGNED_URL_SECONDS = 60;
  private static final int SEGMENT_PAGE_SIZE = 24;
  private final SessionRepository sessions;
  private final SessionRecordingPlaybackStore store;
  private final BrowserCapacityApplicationService capacity;
  private final SessionRecordingPlaybackNodeGateway nodeAccess;
  private final AuditApplicationService audit;

  public SessionRecordingPlaybackApplicationService(
      SessionRepository sessions,
      SessionRecordingPlaybackStore store,
      BrowserCapacityApplicationService capacity,
      SessionRecordingPlaybackNodeGateway nodeAccess,
      AuditApplicationService audit) {
    this.sessions = sessions;
    this.store = store;
    this.capacity = capacity;
    this.nodeAccess = nodeAccess;
    this.audit = audit;
  }

  public RecordingPlaybackGrantView create(
      String sessionId,
      String recordingId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CreateRecordingPlaybackGrantRequest request) {
    requireTenant(sessionId, tenantId);
    store
        .findAvailableRecording(tenantId, sessionId, recordingId, Instant.now())
        .orElseThrow(() -> new RecordingPlaybackNotFoundException("RECORDING_NOT_FOUND"));
    var existing = store.findGrantByIdempotency(tenantId, actorId, idempotencyKey);
    if (existing.isPresent()) {
      requireSame(existing.orElseThrow(), sessionId, recordingId, request.purpose());
      return existing.orElseThrow();
    }
    var now = Instant.now();
    var grantId = "rgr_" + UUID.randomUUID().toString().replace("-", "");
    if (!store.insertGrant(
        grantId,
        tenantId,
        sessionId,
        recordingId,
        actorId,
        request.purpose(),
        idempotencyKey,
        requestId,
        now.plus(GRANT_LIFETIME),
        now)) {
      var raced =
          store
              .findGrantByIdempotency(tenantId, actorId, idempotencyKey)
              .orElseThrow(
                  () ->
                      new RecordingPlaybackRejectedException(
                          "RECORDING_PLAYBACK_IDEMPOTENCY_CONFLICT"));
      requireSame(raced, sessionId, recordingId, request.purpose());
      return raced;
    }
    appendAudit(
        tenantId,
        sessionId,
        actorId,
        grantId,
        "RECORDING_PLAYBACK_GRANTED",
        "COMMITTED",
        request.purpose().name(),
        requestId);
    return store
        .findGrantByIdempotency(tenantId, actorId, idempotencyKey)
        .orElseThrow(
            () -> new RecordingPlaybackRejectedException("RECORDING_PLAYBACK_STATE_UNAVAILABLE"));
  }

  public RedeemRecordingPlaybackGrantResponse redeem(
      String sessionId, String grantId, String tenantId, String actorId, String requestId) {
    requireTenant(sessionId, tenantId);
    var claim = store.claim(tenantId, sessionId, grantId, actorId, Instant.now());
    if (!capacity.nodeHasCapability(claim.nodeId(), "recordingPlayback", "presigned-segments-v1")) {
      return fail(
          claim, tenantId, sessionId, actorId, requestId, "RECORDING_PLAYBACK_NODE_UNAVAILABLE");
    }
    SignedRecordingPlayback signed;
    try {
      signed =
          nodeAccess.sign(
              new SignRecordingPlaybackRequest(
                  grantId,
                  claim.nodeId(),
                  tenantId,
                  claim.profileId(),
                  sessionId,
                  claim.recordingId(),
                  claim.manifestSha256(),
                  claim.manifestBytes(),
                  claim.segmentCount(),
                  claim.frameCount(),
                  claim.redactedFrameCount(),
                  claim.redactedRegionCount(),
                  claim.redactionPolicyVersion(),
                  claim.startedAt().toEpochMilli(),
                  claim.endedAt().toEpochMilli(),
                  0,
                  SEGMENT_PAGE_SIZE,
                  SIGNED_URL_SECONDS));
    } catch (RuntimeException exception) {
      store.failGrant(grantId, safeFailureCode(exception), Instant.now());
      appendAudit(
          tenantId,
          sessionId,
          actorId,
          grantId,
          "RECORDING_PLAYBACK_REDEEMED",
          "FAILED",
          safeFailureCode(exception),
          requestId);
      throw exception;
    }
    var redeemedAt = Instant.now();
    var accessExpiresAt = redeemedAt.plus(PLAYBACK_LIFETIME);
    store.commitGrant(grantId, signed.nodeId(), accessExpiresAt, redeemedAt);
    appendAudit(
        tenantId,
        sessionId,
        actorId,
        grantId,
        "RECORDING_PLAYBACK_REDEEMED",
        "COMMITTED",
        "ONE_TIME_REDEEMED",
        requestId);
    return new RedeemRecordingPlaybackGrantResponse(
        signed.grantId(),
        signed.recordingId(),
        signed.manifestSha256(),
        signed.frameCount(),
        signed.redactedFrameCount(),
        signed.redactedRegionCount(),
        signed.redactionPolicyVersion(),
        signed.expiresAt(),
        accessExpiresAt,
        signed.nextSegmentOffset(),
        signed.segments().stream()
            .map(
                segment ->
                    new RecordingPlaybackSegment(
                        segment.sequence(),
                        segment.contentSha256(),
                        segment.contentBytes(),
                        segment.frameCount(),
                        segment.startedAtMs(),
                        segment.endedAtMs(),
                        segment.downloadUrl()))
            .toList());
  }

  public RedeemRecordingPlaybackGrantResponse page(
      String sessionId,
      String grantId,
      long segmentOffset,
      String tenantId,
      String actorId,
      String requestId) {
    requireTenant(sessionId, tenantId);
    if (segmentOffset < 0) {
      throw new RecordingPlaybackRejectedException("RECORDING_PLAYBACK_OFFSET_INVALID");
    }
    var claim = store.page(tenantId, sessionId, grantId, actorId, segmentOffset, Instant.now());
    if (!capacity.nodeHasCapability(claim.nodeId(), "recordingPlayback", "presigned-segments-v1")) {
      throw new RecordingPlaybackRejectedException("RECORDING_PLAYBACK_NODE_UNAVAILABLE");
    }
    SignedRecordingPlayback signed;
    try {
      signed =
          nodeAccess.sign(
              new SignRecordingPlaybackRequest(
                  grantId,
                  claim.nodeId(),
                  tenantId,
                  claim.profileId(),
                  sessionId,
                  claim.recordingId(),
                  claim.manifestSha256(),
                  claim.manifestBytes(),
                  claim.segmentCount(),
                  claim.frameCount(),
                  claim.redactedFrameCount(),
                  claim.redactedRegionCount(),
                  claim.redactionPolicyVersion(),
                  claim.startedAt().toEpochMilli(),
                  claim.endedAt().toEpochMilli(),
                  segmentOffset,
                  SEGMENT_PAGE_SIZE,
                  SIGNED_URL_SECONDS));
    } catch (RuntimeException exception) {
      appendAudit(
          tenantId,
          sessionId,
          actorId,
          grantId,
          "RECORDING_PLAYBACK_PAGE_ACCESSED",
          "FAILED",
          safeFailureCode(exception),
          requestId);
      throw exception;
    }
    appendAudit(
        tenantId,
        sessionId,
        actorId,
        grantId,
        "RECORDING_PLAYBACK_PAGE_ACCESSED",
        "COMMITTED",
        "OFFSET_" + segmentOffset,
        requestId);
    return new RedeemRecordingPlaybackGrantResponse(
        signed.grantId(),
        signed.recordingId(),
        signed.manifestSha256(),
        signed.frameCount(),
        signed.redactedFrameCount(),
        signed.redactedRegionCount(),
        signed.redactionPolicyVersion(),
        signed.expiresAt(),
        claim.accessExpiresAt(),
        signed.nextSegmentOffset(),
        signed.segments().stream()
            .map(
                segment ->
                    new RecordingPlaybackSegment(
                        segment.sequence(),
                        segment.contentSha256(),
                        segment.contentBytes(),
                        segment.frameCount(),
                        segment.startedAtMs(),
                        segment.endedAtMs(),
                        segment.downloadUrl()))
            .toList());
  }

  private RedeemRecordingPlaybackGrantResponse fail(
      SessionRecordingPlaybackStore.RecordingPlaybackClaim claim,
      String tenantId,
      String sessionId,
      String actorId,
      String requestId,
      String errorCode) {
    store.failGrant(claim.grantId(), errorCode, Instant.now());
    appendAudit(
        tenantId,
        sessionId,
        actorId,
        claim.grantId(),
        "RECORDING_PLAYBACK_REDEEMED",
        "FAILED",
        errorCode,
        requestId);
    throw new RecordingPlaybackRejectedException(errorCode);
  }

  private void requireTenant(String sessionId, String tenantId) {
    if (!tenantId.equals(sessions.require(sessionId).tenantId())) {
      throw new SessionNotFoundException(sessionId);
    }
  }

  private static void requireSame(
      RecordingPlaybackGrantView existing,
      String sessionId,
      String recordingId,
      RecordingPlaybackPurpose purpose) {
    if (!existing.sessionId().equals(sessionId)
        || !existing.recordingId().equals(recordingId)
        || existing.purpose() != purpose) {
      throw new RecordingPlaybackRejectedException("RECORDING_PLAYBACK_IDEMPOTENCY_CONFLICT");
    }
  }

  private static String safeFailureCode(RuntimeException exception) {
    if (exception instanceof RecordingPlaybackNodeRejectedException) {
      return "RECORDING_PLAYBACK_OBJECT_REJECTED";
    }
    return "RECORDING_PLAYBACK_NODE_FAILED";
  }

  private void appendAudit(
      String tenantId,
      String sessionId,
      String actorId,
      String grantId,
      String action,
      String result,
      String reason,
      String requestId) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "SESSION_RECORDING",
            "USER",
            actorId,
            "RECORDING_PLAYBACK_GRANT",
            grantId,
            action,
            result,
            Map.of("reason", reason),
            requestId));
  }

  public static final class RecordingPlaybackRejectedException extends RuntimeException {
    public RecordingPlaybackRejectedException(String message) {
      super(message);
    }
  }

  public static final class RecordingPlaybackNotFoundException extends RuntimeException {
    public RecordingPlaybackNotFoundException(String message) {
      super(message);
    }
  }
}
