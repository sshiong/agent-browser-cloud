package io.browsercloud.api;

import java.time.Instant;

public record SecureDebugSessionView(
    String debugSessionId,
    String breakGlassRequestId,
    String resourceType,
    String resourceId,
    String operatorId,
    String state,
    Instant startedAt,
    Instant expiresAt,
    Instant endedAt,
    String endReason,
    int accessCount,
    Instant lastAccessAt,
    String evidenceHeadHash) {}
