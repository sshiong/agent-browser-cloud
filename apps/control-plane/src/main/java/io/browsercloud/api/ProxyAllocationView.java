package io.browsercloud.api;

import java.time.Instant;

public record ProxyAllocationView(
    String allocationId,
    String sessionId,
    String providerId,
    String protocol,
    String state,
    String exitIp,
    String country,
    String asn,
    Instant allocatedAt,
    Instant verifiedAt,
    Instant releasedAt,
    Instant updatedAt) {}
