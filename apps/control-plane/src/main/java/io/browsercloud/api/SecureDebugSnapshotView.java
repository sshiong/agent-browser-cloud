package io.browsercloud.api;

import java.time.Instant;

/**
 * Purpose-limited debug projection. It intentionally excludes full URL/query, title, DOM text,
 * target names and bounds, screenshots, cookies and profile content.
 */
public record SecureDebugSnapshotView(
    String debugSessionId,
    String sessionId,
    String sessionState,
    String runtimeBuildId,
    long contextEpoch,
    long browserGeneration,
    long networkRevision,
    String urlOrigin,
    long stateVersion,
    long targetRevision,
    String stateQuality,
    String stateHash,
    int interactiveTargetCount,
    int sensitiveTargetCount,
    Instant capturedAt,
    int accessCount,
    String accessEvidenceHash,
    String dataClassification,
    String fieldProjection) {}
