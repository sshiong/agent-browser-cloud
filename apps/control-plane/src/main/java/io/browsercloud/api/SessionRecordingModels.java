package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

/** Public immutable recording-manifest metadata; storage coordinates are never exposed. */
public final class SessionRecordingModels {

  private SessionRecordingModels() {}

  public record RecordingView(
      String recordingId,
      String nodeId,
      long segmentCount,
      long frameCount,
      long droppedFrames,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      String manifestSha256,
      long manifestBytes,
      Instant startedAt,
      Instant endedAt,
      Instant retentionUntil,
      boolean legalHold) {}

  public record RecordingListResponse(List<RecordingView> items, int limit, int offset) {
    public RecordingListResponse {
      items = List.copyOf(items);
    }
  }
}
