package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;

public final class CoordinatorRouteModels {

  private CoordinatorRouteModels() {}

  public record TenantRouteView(
      String tenantId,
      String state,
      int activeVirtualPartitions,
      long activeRouteEpoch,
      Integer pendingVirtualPartitions,
      Long pendingRouteEpoch,
      String activeMigrationId,
      long version,
      Instant updatedAt) {}

  public record RequestTenantRouteMigrationRequest(
      @Min(1) long expectedRouteEpoch, @Min(1) @Max(256) int targetVirtualPartitions) {}

  public record TenantRouteMigrationView(
      String migrationId,
      String tenantId,
      long sourceRouteEpoch,
      long targetRouteEpoch,
      int sourceVirtualPartitions,
      int targetVirtualPartitions,
      String state,
      int totalSessions,
      int migratedSessions,
      int blockedSessions,
      String requestedBy,
      String requestId,
      String failureCode,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}
}
