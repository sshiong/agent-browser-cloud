package io.browsercloud.application;

import java.time.Instant;

public interface ProfileExportAccessNodeGateway {

  SignedProfileExport sign(SignProfileExportRequest request);

  record SignProfileExportRequest(
      String grantId,
      String nodeId,
      String tenantId,
      String profileId,
      String checkpointId,
      int expiresInSeconds) {}

  record SignedProfileExport(
      String grantId,
      String nodeId,
      String profileId,
      String checkpointId,
      String archiveSha256,
      long archiveSizeBytes,
      String downloadUrl,
      Instant expiresAt) {}

  final class ProfileExportNodeUnavailableException extends RuntimeException {
    public ProfileExportNodeUnavailableException(String message) {
      super(message);
    }

    public ProfileExportNodeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  final class ProfileExportNodeRejectedException extends RuntimeException {
    public ProfileExportNodeRejectedException(String message) {
      super(message);
    }

    public ProfileExportNodeRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
