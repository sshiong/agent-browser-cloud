package io.browsercloud.api;

import io.browsercloud.domain.resource.ExecutionEnvironment;
import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import io.browsercloud.domain.resource.ResourcePolicyStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SessionResourceModels {
  private SessionResourceModels() {}

  public record PolicyView(
      ResourcePolicyMode mode,
      ExecutionEnvironment executionEnvironment,
      String minimumTemplate,
      String resolvedTemplate,
      int maximumCpuMillis,
      int maximumMemoryMib,
      Double maximumCostPerHour,
      int scaleUpWindowSeconds,
      int scaleDownWindowSeconds,
      int adjustmentCooldownSeconds,
      boolean allowMigration,
      boolean allowHibernate,
      boolean blockMigrationDuringHumanTakeover,
      MaximumReachedPolicy onMaximumReached) {}

  public record AllocationView(
      String nodeId,
      String template,
      Integer cpuMillis,
      Integer memoryRequestMib,
      Integer memoryLimitMib,
      Integer tabBudget,
      Integer stateCollectorBudgetPercent,
      Integer remoteDesktopBitrateKbps,
      Integer extensionCpuWeight,
      Integer mediaEncoderSlots,
      Integer mediaEncoderSlotLimit,
      Boolean backgroundTabsFrozen,
      Boolean newTabsBlocked,
      List<String> pausedExtensionIds,
      Integer successTraceSamplePercent,
      Integer successScreenshotSamplePercent,
      Integer observerFrameRateFps,
      Boolean videoRecordingRequested,
      Boolean videoRecordingEnabled,
      String placementState) {}

  public record UsageView(
      Double cpuPercent,
      Integer memoryRssMib,
      Double memoryPercentOfLimit,
      Double memoryPsiSomeAvg10,
      Integer rendererCount,
      Integer tabCount,
      Integer agentActionLatencyMs,
      Integer stateDiffQueueDepth,
      Long profileIoBytesPerSecond,
      Double extensionCpuPercent,
      Integer extensionMemoryMib,
      Integer remoteDesktopFrameAgeMs,
      Double mediaEncoderPercent,
      Instant observedAt) {}

  public record UsagePoint(
      Instant observedAt, Double cpuPercent, Integer memoryRssMib, Double memoryPercentOfLimit) {}

  public record CostPoint(Instant observedAt, BigDecimal hourlyCost, String pricingVersion) {}

  public record CostView(
      BigDecimal currentHourlyCost,
      Double maximumHourlyCost,
      String pricingVersion,
      Instant lastEvaluatedAt,
      List<CostPoint> trend) {}

  public record SessionResourceView(
      String sessionId,
      PolicyView policy,
      AllocationView allocation,
      UsageView usage,
      List<UsagePoint> usageSamples,
      CostView cost,
      ResourceAdjustmentView currentAdjustment,
      ResourcePolicyStatus status,
      String statusReason,
      String dataFreshness,
      Instant lastEvaluatedAt,
      Instant lastAdjustedAt) {}

  public record ResourceAdjustmentView(
      String operationId,
      String state,
      String reason,
      String failureCode,
      Map<String, Object> oldResources,
      Map<String, Object> requestedResources,
      Instant requestedAt,
      Instant executingAt,
      Instant acknowledgedAt,
      Instant completedAt,
      String reconciliationOperationId,
      Instant reconciledAt,
      Instant updatedAt) {}

  public record ResourceEventView(
      String eventId,
      Instant occurredAt,
      String eventType,
      String reason,
      Map<String, Object> oldResources,
      Map<String, Object> newResources,
      String decisionSource,
      String operationId,
      String requestId,
      String result) {}

  public record ResourceEventListResponse(List<ResourceEventView> items, int limit, int offset) {}

  public record ResourceStreamEventView(
      long sequence, String changeType, String entityId, Instant occurredAt, boolean replayed) {}

  public record ResourcePolicyOperationResponse(
      String operationId, String state, PolicyView resourcePolicy) {}

  public record RecordResourceSampleRequest(
      @NotBlank @Pattern(regexp = "^node_[a-zA-Z0-9_-]{1,123}$") String nodeId,
      @DecimalMin("0.0") @DecimalMax("100.0") Double cpuPercent,
      @Min(0) Integer memoryRssMib,
      @DecimalMin("0.0") Double memoryPsiSomeAvg10,
      @Min(0) Integer rendererCount,
      @Min(0) Integer tabCount,
      @Min(0) Integer mainThreadBlockedMs,
      @Min(0) Integer agentActionLatencyMs,
      @Min(0) Integer stateDiffQueueDepth,
      @Min(0) Long profileIoBytesPerSecond,
      @DecimalMin("0.0") @DecimalMax("100.0") Double extensionCpuPercent,
      @Min(0) Integer extensionMemoryMib,
      @Min(0) Integer remoteDesktopFrameAgeMs,
      @DecimalMin("0.0") @DecimalMax("100.0") Double mediaEncoderPercent,
      @Pattern(regexp = "^(OOM|CRASH|DISK_FULL|BROWSER_UNRESPONSIVE|SECURITY_ISOLATION_FAILURE)?$")
          String dangerEvent,
      @PastOrPresent Instant observedAt) {}

  /** Complete Browser Node readback of the allocation that is actually active at the actuator. */
  public record NodeResourceAllocationReadback(
      @NotBlank @Pattern(regexp = "^node_[a-zA-Z0-9_-]{1,123}$") String nodeId,
      @NotBlank @Pattern(regexp = "^L[1-5]$") String resourceClass,
      @Min(1) int cpuMillis,
      @Min(1) int memoryRequestMib,
      @Min(1) int memoryLimitMib,
      @Min(1) int pidLimit,
      @Min(1) int tabBudget,
      @Min(10) @Max(100) int stateCollectorBudgetPercent,
      @Min(0) @Max(100_000) int remoteDesktopBitrateKbps,
      @Min(1) @Max(10_000) int extensionCpuWeight,
      @Min(0) int mediaEncoderSlots,
      boolean backgroundTabsFrozen,
      boolean newTabsBlocked,
      List<String> pausedExtensionIds,
      @Min(1) @Max(100) int successTraceSamplePercent,
      @Min(0) @Max(60) int observerFrameRateFps,
      boolean videoRecordingEnabled,
      @Min(1) @Max(100) int successScreenshotSamplePercent,
      @PastOrPresent Instant observedAt) {
    public NodeResourceAllocationReadback {
      pausedExtensionIds = pausedExtensionIds == null ? List.of() : List.copyOf(pausedExtensionIds);
    }
  }
}
