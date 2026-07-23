package io.browsercloud.api;

import io.browsercloud.domain.session.SessionState;
import java.time.Instant;

/** Session 视图。 */
public record SessionView(
    String sessionId,
    String tenantId,
    SessionState state,
    String nodeId,
    String runtimeBuildId,
    long contextEpoch,
    long browserGeneration,
    OperationView currentOperation,
    Instant createdAt,
    Instant updatedAt) {}
