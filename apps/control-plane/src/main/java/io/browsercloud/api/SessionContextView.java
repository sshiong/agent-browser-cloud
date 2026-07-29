package io.browsercloud.api;

import io.browsercloud.domain.session.SessionState;
import java.time.Instant;

/** Session 上下文视图。 */
public record SessionContextView(
    String sessionId,
    String tenantId,
    String profileId,
    String nodeId,
    String runtimeBuildId,
    String isolationProfileId,
    String proxyBindingId,
    long coordinatorTerm,
    long contextEpoch,
    long browserGeneration,
    long networkRevision,
    String resourceTemplate,
    SessionState state,
    String policyHash,
    Instant createdAt,
    Instant updatedAt) {}
