package io.browsercloud.api;

import java.time.Instant;

public record ProfileView(
    String profileId,
    String tenantId,
    String name,
    String description,
    String latestCheckpointId,
    Long latestCheckpointEpoch,
    long profileWriteEpoch,
    long coreSizeBytes,
    long checkpointFileCount,
    String restoreStatus,
    String state,
    Instant createdAt,
    Instant updatedAt,
    Instant lastCheckpointAt) {}
