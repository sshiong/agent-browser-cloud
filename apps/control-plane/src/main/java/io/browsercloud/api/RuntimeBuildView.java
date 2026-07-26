package io.browsercloud.api;

import java.time.Instant;

public record RuntimeBuildView(
    String buildId,
    String engine,
    String version,
    String platform,
    String securityTier,
    String regressionStatus,
    boolean signatureVerified,
    String signature,
    String sbomUrl,
    Instant validatedAt,
    Instant releasedAt,
    Instant createdAt) {}
