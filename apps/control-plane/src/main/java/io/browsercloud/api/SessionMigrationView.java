package io.browsercloud.api;

import java.time.Instant;

public record SessionMigrationView(
    String migrationId,
    String sessionId,
    String sourceNodeId,
    String targetNodeId,
    long sourceContextEpoch,
    Long targetContextEpoch,
    String checkpointId,
    String hibernateOperationId,
    String restoreOperationId,
    String resyncRequestId,
    String phase,
    String recoveryResult,
    String failureReason,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {}
