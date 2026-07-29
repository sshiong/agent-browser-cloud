package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.List;

/** Session 视图。 */
public record SessionView(
    String sessionId,
    String displayName,
    String tenantId,
    String profileId,
    String groupId,
    List<WorkspaceTagModels.WorkspaceTagSummary> tags,
    boolean humanTakeoverEnabled,
    AgentPolicy agentPolicy,
    List<String> extensionIds,
    String region,
    String resourceTemplate,
    SessionState state,
    String nodeId,
    String runtimeBuildId,
    String proxyBindingId,
    long contextEpoch,
    long browserGeneration,
    OperationView currentOperation,
    Instant createdAt,
    Instant updatedAt) {}
