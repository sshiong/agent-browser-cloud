package io.browsercloud.api;

import java.time.Instant;

public record KeyRotationRequestView(
    String rotationId,
    String keyScope,
    String oldKeyId,
    String newKeyId,
    String rotationTrigger,
    String reason,
    int requestedOverlapMinutes,
    String state,
    String requestedBy,
    String approvedBy,
    String completedBy,
    String revokedBy,
    Instant requestedAt,
    Instant approvedAt,
    Instant startedAt,
    Instant completedAt,
    Instant revokedAt,
    Instant overlapUntil,
    int progressPercent,
    Boolean newKeyWriteVerified,
    Boolean oldKeyReadVerified,
    Boolean plaintextRejected,
    Integer affectedWorkloads,
    String verificationReference,
    String approvalEvidenceHash,
    String completionEvidenceHash) {}
