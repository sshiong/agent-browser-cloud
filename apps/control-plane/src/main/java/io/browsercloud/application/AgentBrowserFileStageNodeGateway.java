package io.browsercloud.application;

import java.io.InputStream;

/** Direct, bounded file-byte stream to the exact Node holding the Session Runtime. */
public interface AgentBrowserFileStageNodeGateway {
  StageResult stage(StageRequest request, InputStream content);

  record StageRequest(
      String uploadId,
      String tenantId,
      String sessionId,
      String nodeId,
      long coordinatorTerm,
      long contextEpoch,
      String filename,
      String mimeType,
      String contentSha256,
      long contentBytes) {}

  record StageResult(
      String uploadId, String nodeId, String sessionId, String contentSha256, long contentBytes) {}

  final class StageRejectedException extends RuntimeException {
    public StageRejectedException(String code) {
      super(code);
    }

    public StageRejectedException(String code, Throwable cause) {
      super(code, cause);
    }
  }

  final class StageUnavailableException extends RuntimeException {
    public StageUnavailableException(String code, Throwable cause) {
      super(code, cause);
    }
  }
}
