package io.browsercloud.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public final class ProfileExportModels {

  private ProfileExportModels() {}

  public enum ProfileExportPurpose {
    INCIDENT_RESPONSE,
    SUPPORT_DIAGNOSTICS,
    COMPLIANCE_EXPORT,
    TENANT_BACKUP
  }

  public record CreateProfileExportGrantRequest(@NotNull ProfileExportPurpose purpose) {}

  public record ProfileExportGrantView(
      String grantId,
      String profileId,
      String checkpointId,
      long checkpointEpoch,
      ProfileExportPurpose purpose,
      String state,
      Instant expiresAt,
      Instant createdAt,
      Instant redeemedAt,
      String archiveSha256,
      Long archiveSizeBytes,
      String errorCode,
      String requestId) {}

  /** Ephemeral only: the signed URL is never persisted by the Control Plane. */
  public record RedeemProfileExportResponse(
      String grantId,
      String profileId,
      String checkpointId,
      String archiveSha256,
      long archiveSizeBytes,
      String downloadUrl,
      Instant expiresAt) {}
}
