package io.browsercloud.application;

import java.time.Instant;

/** mTLS-only request for a Browser Node to sign one exact, already committed evidence object. */
public interface SessionEvidenceAccessNodeGateway {

  SignedEvidenceAccess sign(SignEvidenceAccessRequest request);

  record SignEvidenceAccessRequest(
      String grantId,
      String nodeId,
      String tenantId,
      String profileId,
      String sessionId,
      String evidenceId,
      String contentSha256,
      long contentBytes,
      int expiresInSeconds) {}

  record SignedEvidenceAccess(
      String grantId, String nodeId, String evidenceId, String downloadUrl, Instant expiresAt) {}

  final class EvidenceAccessNodeUnavailableException extends RuntimeException {
    public EvidenceAccessNodeUnavailableException(String message) {
      super(message);
    }

    public EvidenceAccessNodeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  final class EvidenceAccessNodeRejectedException extends RuntimeException {
    public EvidenceAccessNodeRejectedException(String message) {
      super(message);
    }

    public EvidenceAccessNodeRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
