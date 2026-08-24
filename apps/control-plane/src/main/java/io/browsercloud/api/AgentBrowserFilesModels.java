package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

/** Coarse Browser file state. URLs and Node-local paths are intentionally absent. */
public final class AgentBrowserFilesModels {
  private AgentBrowserFilesModels() {}

  public record DownloadView(
      String downloadId,
      String filename,
      String mimeType,
      Long size,
      long receivedBytes,
      Double progress,
      String status,
      Instant startedAt,
      Instant updatedAt) {}

  public record DownloadListView(
      String stateCursor, boolean evidenceFresh, boolean dataStale, List<DownloadView> downloads) {
    public DownloadListView {
      downloads = List.copyOf(downloads);
    }
  }

  public record FileUploadView(
      String uploadId,
      String operationId,
      String sessionId,
      String targetRef,
      String filename,
      String mimeType,
      String contentSha256,
      long contentBytes,
      String state,
      String errorCode,
      Long stateVersionAfter,
      String requestId,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}
}
