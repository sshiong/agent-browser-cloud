package io.browsercloud.api;

import java.time.Instant;

public record RuntimeBuildView(
    String buildId,
    String engine,
    String version,
    String platform,
    String securityTier,
    String regressionStatus,
    String releaseChannel,
    boolean signatureVerified,
    String signature,
    String artifactDigest,
    String signingKeyId,
    String sbomUrl,
    Instant validatedAt,
    Instant releasedAt,
    Instant disabledAt,
    String disabledBy,
    Instant createdAt) {}
