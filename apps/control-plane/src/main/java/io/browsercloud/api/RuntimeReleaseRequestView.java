package io.browsercloud.api;

import java.time.Instant;

public record RuntimeReleaseRequestView(
    String releaseId,
    String buildId,
    String targetChannel,
    String reason,
    String state,
    String requestedBy,
    String approvedBy,
    String rejectedBy,
    Instant requestedAt,
    Instant decidedAt,
    String evidenceHash) {}
