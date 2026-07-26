package io.browsercloud.api;

import java.time.Instant;
import java.util.Map;

public record AuditEventView(
    String eventId,
    long sequenceNo,
    String sessionId,
    String eventType,
    String actorType,
    String actorId,
    String resourceType,
    String resourceId,
    String action,
    String result,
    Map<String, Object> details,
    String previousEventHash,
    String eventHash,
    String requestId,
    Instant retentionUntil,
    boolean legalHold,
    Instant createdAt) {}
