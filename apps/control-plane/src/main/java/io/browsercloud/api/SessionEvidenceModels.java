package io.browsercloud.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Public screenshot evidence metadata. Raw object-storage coordinates are intentionally omitted.
 */
public final class SessionEvidenceModels {

  private SessionEvidenceModels() {}

  public record EvidenceView(
      String evidenceId,
      String evidenceKind,
      String taskId,
      String stepId,
      String commandId,
      boolean mandatory,
      String result,
      String contentSha256,
      long contentBytes,
      Instant capturedAt,
      String errorCode) {}

  public record EvidenceListResponse(List<EvidenceView> items, int limit, int offset) {
    public EvidenceListResponse {
      items = List.copyOf(items);
    }
  }

  public enum EvidencePurpose {
    INCIDENT_RESPONSE,
    CHANGE_VALIDATION,
    SUPPORT_DIAGNOSTICS,
    COMPLIANCE_AUDIT
  }

  public record CaptureEvidenceRequest(@NotNull EvidencePurpose purpose) {}

  public record EvidenceCaptureView(
      String captureId,
      String sessionId,
      EvidencePurpose purpose,
      String state,
      String evidenceId,
      String errorCode,
      String commandId,
      String requestId,
      Instant createdAt,
      Instant completedAt) {}

  public record CreateEvidenceAccessGrantRequest(@NotNull EvidencePurpose purpose) {}

  public record EvidenceAccessGrantView(
      String grantId,
      String sessionId,
      String evidenceId,
      EvidencePurpose purpose,
      String state,
      Instant expiresAt,
      Instant createdAt,
      Instant redeemedAt,
      String errorCode,
      String requestId) {}

  /**
   * Ephemeral response only. The URL grants read access to one immutable screenshot object and is
   * never persisted by the Control Plane.
   */
  public record RedeemEvidenceAccessResponse(
      String grantId, String evidenceId, String downloadUrl, Instant expiresAt) {}
}
