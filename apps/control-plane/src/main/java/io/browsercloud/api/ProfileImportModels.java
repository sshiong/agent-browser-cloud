package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

public final class ProfileImportModels {

  private ProfileImportModels() {}

  public record ProfileImportView(
      String importId,
      String operationId,
      String profileId,
      String profileName,
      String runtimeBuildId,
      String archiveSha256,
      long archiveSizeBytes,
      String state,
      String nodeId,
      String checkpointId,
      Long checkpointEpoch,
      Long profileWriteEpoch,
      Long coreSizeBytes,
      Long checkpointFileCount,
      String errorCode,
      String requestId,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}

  public record ProfileImportListResponse(List<ProfileImportView> items, int total) {}
}
