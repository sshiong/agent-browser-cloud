package io.browsercloud.api;

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
}
