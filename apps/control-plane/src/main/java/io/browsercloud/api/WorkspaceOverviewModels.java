package io.browsercloud.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Tenant-safe aggregate views used by the shared Web/Tauri workspace overview. */
public final class WorkspaceOverviewModels {
  private WorkspaceOverviewModels() {}

  public record WorkspaceOverviewResponse(
      SessionSummary sessions,
      OperationSummary operations,
      BrowserNodeSummary browserNodes,
      ProxySummary proxies,
      AgentSummary agents,
      CostSummary cost,
      SecuritySummary security,
      long cursor,
      Instant generatedAt) {}

  public record SessionSummary(
      long total, long running, long pending, long unhealthy, long hibernated, long terminated) {}

  public record OperationSummary(long active) {}

  public record BrowserNodeSummary(
      boolean visible,
      long total,
      long ready,
      long constrained,
      long activeSessions,
      long maximumSessions,
      long reservedCpuMillis,
      long certifiedCpuMillis,
      long reservedMemoryMib,
      long certifiedMemoryMib) {}

  public record ProxySummary(long activeAllocations, long boundSessions) {}

  public record AgentSummary(
      long active, long awaitingHuman, long pausedByResourcePolicy, long failedLast24Hours) {}

  public record CostSummary(BigDecimal currentHourlyUsd, long activeSessionsWithoutCurrentPrice) {}

  public record SecuritySummary(long warningLast24Hours, long criticalLast24Hours) {}

  public record WorkspaceOverviewEvent(
      long sequence, String changeType, Instant occurredAt, boolean replayed) {}
}
