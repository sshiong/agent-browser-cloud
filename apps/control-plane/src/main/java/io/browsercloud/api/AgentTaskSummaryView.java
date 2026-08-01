package io.browsercloud.api;

import io.browsercloud.domain.agent.AgentPolicy;
import java.time.Instant;

/** Lightweight task projection for bounded tenant queues. */
public record AgentTaskSummaryView(
    String taskId,
    String sessionId,
    String goal,
    String state,
    String riskClass,
    String intentDecision,
    String blockedReason,
    AgentPolicy agentPolicy,
    int currentStep,
    int totalSteps,
    int securityEventCount,
    Instant createdAt,
    Instant updatedAt) {}
