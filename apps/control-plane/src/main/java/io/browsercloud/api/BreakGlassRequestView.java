package io.browsercloud.api;

import java.time.Instant;

public record BreakGlassRequestView(
    String requestId,
    String ticketId,
    String reason,
    String resourceType,
    String resourceId,
    String requestedScope,
    String state,
    String requestedBy,
    String approvedBy,
    String rejectedBy,
    String revokedBy,
    String evidenceHash,
    Instant requestedAt,
    Instant approvedAt,
    Instant rejectedAt,
    Instant revokedAt,
    Instant expiresAt,
    Instant reviewedAt) {}
