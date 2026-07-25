package io.browsercloud.api;

import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;

/** Session 视图。 */
public record SessionView(
    String sessionId,
    String displayName,
    String tenantId,
    String profileId,
    String region,
    ResourceClass resourceClass,
    SessionState state,
    String nodeId,
    String runtimeBuildId,
    String proxyBindingId,
    long contextEpoch,
    long browserGeneration,
    OperationView currentOperation,
    Instant createdAt,
    Instant updatedAt) {}
