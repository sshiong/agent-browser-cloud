package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

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
    String targetCleanupOperationId,
    int targetAttempt,
    int maximumTargetAttempts,
    List<String> failedTargetNodeIds,
    String lastTargetFailureReason,
    String resyncRequestId,
    String phase,
    String recoveryResult,
    String failureReason,
    int autoRecoveryAttempts,
    int autoRecoveryMaximum,
    BusinessRecoveryModels.BusinessRecoveryActionView latestRecoveryAction,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {}
