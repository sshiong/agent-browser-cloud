package io.browsercloud.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class EnterpriseOperationsModels {

  private EnterpriseOperationsModels() {}

  public record StartRuntimeValidationRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String buildId,
      @NotBlank @Size(max = 64) String suiteVersion,
      @NotBlank @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String environmentDigest,
      @NotBlank @Size(max = 128) String replayDatasetId,
      @NotBlank @Size(max = 64) String persona,
      @Pattern(regexp = "^[a-z0-9_.-]{1,64}$") String browserEngine,
      @Pattern(regexp = "^[A-Za-z0-9_.+-]{1,64}$") String browserVersion,
      @Pattern(regexp = "^[a-z0-9_.-]{1,64}$") String operatingSystem,
      @Pattern(regexp = "^[a-z0-9_.-]{1,32}$") String architecture,
      Map<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String, Boolean>
          requiredWorkerCapabilities,
      @Min(1) @Max(10) Integer maximumAttempts) {}

  public record RuntimeValidationMatrixCellRequest(
      @NotBlank @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String environmentDigest,
      @NotBlank @Pattern(regexp = "^[a-z0-9_.-]{1,64}$") String browserEngine,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.+-]{1,64}$") String browserVersion,
      @NotBlank @Pattern(regexp = "^[a-z0-9_.-]{1,64}$") String operatingSystem,
      @NotBlank @Pattern(regexp = "^[a-z0-9_.-]{1,32}$") String architecture,
      @NotNull
          Map<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String, Boolean>
              requiredWorkerCapabilities,
      @Min(1) @Max(10) Integer maximumAttempts) {}

  public record StartRuntimeValidationMatrixRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String buildId,
      @NotBlank @Size(max = 64) String suiteVersion,
      @NotBlank @Size(max = 128) String replayDatasetId,
      @NotBlank @Size(max = 64) String persona,
      @NotNull @Size(min = 1, max = 64)
          List<@Valid @NotNull RuntimeValidationMatrixCellRequest> cells) {}

  public record CompleteRuntimeValidationRequest(
      @Min(1) @Max(100000) int requiredTests,
      @Min(0) @Max(100000) int requiredFailures,
      @Min(0) @Max(100000) int optionalTests,
      @Min(0) @Max(100000) int optionalFailures,
      @NotNull Map<String, Boolean> declaredCapabilities,
      @NotNull Map<String, Boolean> observedCapabilities,
      @NotNull @Size(max = 256) List<@Size(max = 128) String> optionalFailureCodes,
      boolean personaConsistent) {}

  public record RuntimeValidationView(
      String validationId,
      String buildId,
      String suiteVersion,
      String environmentDigest,
      String replayDatasetId,
      String persona,
      String state,
      int requiredTests,
      int requiredFailures,
      int optionalTests,
      int optionalFailures,
      Map<String, Boolean> declaredCapabilities,
      Map<String, Boolean> observedCapabilities,
      List<String> optionalFailureCodes,
      String evidenceHash,
      String requestedBy,
      Instant startedAt,
      Instant completedAt,
      RuntimeValidationJobView job) {}

  public record RuntimeValidationJobView(
      String validationId,
      String browserEngine,
      String browserVersion,
      String operatingSystem,
      String architecture,
      Map<String, Boolean> requiredWorkerCapabilities,
      String state,
      int attempt,
      int maximumAttempts,
      String workerId,
      long claimEpoch,
      Instant availableAt,
      Instant leaseExpiresAt,
      Instant lastHeartbeatAt,
      String failureCode,
      String resultHash,
      Instant updatedAt) {}

  public record ClaimRuntimeValidationJobRequest(
      @NotBlank @Pattern(regexp = "^[a-z0-9_.-]{1,64}$") String browserEngine,
      @NotNull @Size(min = 1, max = 64)
          List<@Pattern(regexp = "^[A-Za-z0-9_.+-]{1,64}$") String> browserVersions,
      @NotBlank @Pattern(regexp = "^[a-z0-9_.-]{1,64}$") String operatingSystem,
      @NotBlank @Pattern(regexp = "^[a-z0-9_.-]{1,32}$") String architecture,
      @NotNull
          Map<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String, Boolean> capabilities) {}

  public record RuntimeValidationJobClaimView(
      String claimToken,
      RuntimeValidationView validation,
      Instant leaseExpiresAt,
      long claimEpoch) {}

  public record RuntimeValidationJobClaimRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken) {}

  public record CompleteRuntimeValidationJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @Valid @NotNull CompleteRuntimeValidationRequest result) {}

  public record FailRuntimeValidationJobRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String claimToken,
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,127}$") String failureCode,
      boolean retryable) {}

  public record CreateCostRateRequest(
      @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,32}$") String region,
      @NotBlank
          @Pattern(
              regexp =
                  "^(suspended-v1|standard-lite-v1|standard-v1|interactive-v1|heavy-v1|native-standard-v1)$")
          String resourceTemplate,
      @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal baseHourlyUsd,
      @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal cpuCoreHourlyUsd,
      @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal memoryGibHourlyUsd,
      @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal desktopHourlyUsd,
      @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal gpuHourlyUsd,
      @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal mediaHourlyUsd,
      @NotNull Instant effectiveAt) {}

  public record CostRateView(
      String pricingVersion,
      String region,
      String resourceTemplate,
      BigDecimal baseHourlyUsd,
      BigDecimal cpuCoreHourlyUsd,
      BigDecimal memoryGibHourlyUsd,
      BigDecimal desktopHourlyUsd,
      BigDecimal gpuHourlyUsd,
      BigDecimal mediaHourlyUsd,
      Instant effectiveAt,
      String createdBy,
      Instant createdAt) {}

  public record SessionCostExplanationView(
      String sessionId,
      String nodeId,
      String region,
      String resourceTemplate,
      String pricingVersion,
      int cpuMillis,
      int memoryRequestMib,
      boolean desktop,
      boolean gpu,
      boolean media,
      BigDecimal baseHourlyUsd,
      BigDecimal cpuHourlyUsd,
      BigDecimal memoryHourlyUsd,
      BigDecimal desktopHourlyUsd,
      BigDecimal gpuHourlyUsd,
      BigDecimal mediaHourlyUsd,
      BigDecimal totalHourlyUsd,
      Instant pricedAt) {}

  public record UpsertMediaQuotaRequest(
      @Min(0) @Max(10000) int maxConcurrentStreams, @Min(0) @Max(100000000) int maxBitrateKbps) {}

  public record MediaQuotaView(
      String tenantId,
      int maxConcurrentStreams,
      int maxBitrateKbps,
      long activeStreams,
      long activeBitrateKbps,
      String updatedBy,
      Instant updatedAt) {}

  public record UpsertSloPolicyRequest(
      @NotNull @DecimalMin("0.900000") @DecimalMax(value = "0.999999")
          BigDecimal availabilityTarget,
      @Min(1) @Max(600000) int latencyP95TargetMs,
      @Min(60) @Max(527040) int windowMinutes,
      Boolean releaseFreezeEnabled,
      @DecimalMin("0.000001") @DecimalMax("1000") BigDecimal releaseFreezeBurnRateThreshold,
      @DecimalMin("0") @DecimalMax("999.999999") BigDecimal releaseRecoveryBurnRateThreshold,
      @Min(5) @Max(1440) Integer releaseFreezeWindowMinutes,
      @Min(1) @Max(1440) Integer releaseRecoveryStableMinutes) {}

  public record RecordServiceLevelEventRequest(
      @NotBlank @Pattern(regexp = "^(UNAVAILABLE|LATENCY_BREACH|HEALTHY)$") String eventType,
      @Min(0) @Max(86400) int durationSeconds,
      @Min(0) @Max(600000) Integer latencyP95Ms,
      @NotBlank @Size(max = 128) String source,
      @NotNull Instant occurredAt,
      @Pattern(regexp = "^[A-Z0-9_]{1,64}$") String exclusionCode) {}

  public record UpsertSlaExclusionRequest(
      @NotBlank @Size(max = 256) String description, boolean enabled) {}

  public record SlaExclusionView(
      String tenantId,
      String exclusionCode,
      String description,
      boolean enabled,
      String updatedBy,
      Instant updatedAt) {}

  public record ErrorBudgetView(
      String tenantId,
      BigDecimal availabilityTarget,
      int latencyP95TargetMs,
      int windowMinutes,
      long allowedUnavailableSeconds,
      long consumedUnavailableSeconds,
      long remainingUnavailableSeconds,
      BigDecimal burnRatio,
      String state,
      Instant windowStartedAt,
      Instant calculatedAt) {}

  public record ReleaseFreezeView(
      String tenantId,
      boolean enabled,
      String phase,
      boolean frozen,
      BigDecimal currentBurnRate,
      BigDecimal freezeBurnRateThreshold,
      BigDecimal recoveryBurnRateThreshold,
      int evaluationWindowMinutes,
      int recoveryStableMinutes,
      String reasonCode,
      Instant stableSince,
      Instant frozenAt,
      Instant clearedAt,
      Instant evaluatedAt,
      long version) {}

  public record UpsertRetentionPolicyRequest(
      @NotBlank
          @Pattern(
              regexp =
                  "^(AUDIT|AGENT_EXECUTION|PROFILE_CHECKPOINT|REMOTE_DESKTOP_RECORDING|SECURE_DEBUG)$")
          String dataClass,
      @Min(1) @Max(3650) int retentionDays,
      boolean legalHold,
      @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,32}$") String residencyRegion) {}

  public record RetentionPolicyView(
      String tenantId,
      String dataClass,
      int retentionDays,
      boolean legalHold,
      String residencyRegion,
      String updatedBy,
      Instant updatedAt) {}

  public record CreateDeletionReceiptRequest(
      @NotBlank
          @Pattern(
              regexp =
                  "^(AUDIT|AGENT_EXECUTION|PROFILE_CHECKPOINT|REMOTE_DESKTOP_RECORDING|SECURE_DEBUG)$")
          String dataClass,
      @NotBlank @Size(max = 256) String objectId,
      @NotBlank @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String contentDigest) {}

  public record DeletionReceiptView(
      String receiptId,
      String tenantId,
      String dataClass,
      String objectId,
      String contentDigest,
      Instant policyUpdatedAt,
      String receiptHash,
      String deletedBy,
      Instant deletedAt) {}

  public record UpsertLicenseInventoryRequest(
      @NotBlank @Pattern(regexp = "^(RUNTIME|EXTENSION|SERVICE|SDK)$") String componentType,
      @NotBlank @Size(max = 128) String componentName,
      @NotBlank @Size(max = 64) String componentVersion,
      @NotBlank @Size(max = 64) String licenseId,
      @NotBlank @Size(max = 512) String sourceUrl,
      boolean approved) {}

  public record LicenseInventoryView(
      String componentId,
      String componentType,
      String componentName,
      String componentVersion,
      String licenseId,
      String sourceUrl,
      boolean approved,
      String evidenceHash,
      String updatedBy,
      Instant updatedAt) {}

  public record AuditExportManifestView(
      String exportId,
      String tenantId,
      long fromSequence,
      long toSequence,
      long eventCount,
      String firstEventHash,
      String lastEventHash,
      String manifestHash,
      String signatureAlgorithm,
      String signingKeyId,
      String signature,
      String generatedBy,
      Instant generatedAt) {}

  public record UpsertRegionRequest(
      @NotBlank @Pattern(regexp = "^(PRIMARY|SECONDARY|DR)$") String role,
      @NotBlank @Pattern(regexp = "^(OPEN|CLOSED|FAILOVER_READY)$") String admissionState,
      @Min(0) @Max(86400) int replicationLagSeconds) {}

  public record RegionView(
      String regionId,
      String role,
      String admissionState,
      int replicationLagSeconds,
      Instant lastVerifiedAt,
      String updatedBy) {}

  public record StartRecoveryGameDayRequest(
      @NotBlank @Size(max = 128) String scenario,
      @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,32}$") String sourceRegion,
      @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,32}$") String targetRegion,
      @Min(1) @Max(86400) int rtoTargetSeconds,
      @Min(0) @Max(86400) int rpoTargetSeconds,
      @Pattern(regexp = "^(MANUAL|AUTO)$") String executionMode,
      @Pattern(regexp = "^(TEST|STAGING|PRODUCTION)$") String environment,
      @Valid RecoveryGameDayBlastRadiusRequest blastRadius,
      @Min(30) @Max(7200) Integer maximumDurationSeconds,
      @Pattern(regexp = "^brk_[A-Za-z0-9]{20}$") String approvalRequestId,
      Map<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String, Boolean>
          requiredWorkerCapabilities,
      @Min(1) @Max(10) Integer maximumAttempts) {}

  public record RecoveryGameDayBlastRadiusRequest(
      @NotBlank @Pattern(regexp = "^(TEST_FIXTURE|TENANT|NAMESPACE|REGION)$") String scope,
      @Min(1) @Max(100) int maximumTargets,
      @NotNull @Size(max = 100)
          List<@Pattern(regexp = "^[A-Za-z0-9_.:/-]{1,128}$") String> targetIds) {}

  public record CompleteRecoveryGameDayRequest(
      @Min(0) @Max(86400) int observedRtoSeconds,
      @Min(0) @Max(86400) int observedRpoSeconds,
      @Min(0) int dataLossRecords,
      @Min(0) @Max(86400) Integer detectionTimeSeconds,
      @Min(0) @Max(86400) Integer failoverTimeSeconds,
      @Min(0) Integer staleOperationCount,
      @Min(0) Integer userImpactCount,
      @Min(0) Integer manualSteps,
      @Min(0) @Max(100) Integer runbookAccuracyPercent,
      @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String runnerEvidenceHash,
      Boolean recoveryConfirmed) {}

  public record ClaimRecoveryGameDayJobRequest(
      @NotNull @Size(min = 1, max = 3)
          List<@Pattern(regexp = "^(TEST|STAGING|PRODUCTION)$") String> environments,
      @NotNull @Size(min = 1, max = 32)
          List<@Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String> scenarioCodes,
      @NotNull
          Map<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String, Boolean> capabilities) {}

  public record RecoveryGameDayJobClaimRequest(
      @NotBlank @Size(min = 32, max = 128) String claimToken) {}

  public record UpdateRecoveryGameDayStageRequest(
      @NotBlank @Size(min = 32, max = 128) String claimToken,
      @NotBlank @Pattern(regexp = "^(INJECTING|FAULT_INJECTED|OBSERVING|RECOVERING|VALIDATING)$")
          String stage) {}

  public record CompleteRecoveryGameDayJobRequest(
      @NotBlank @Size(min = 32, max = 128) String claimToken,
      @NotNull @Valid CompleteRecoveryGameDayRequest result) {}

  public record FailRecoveryGameDayJobRequest(
      @NotBlank @Size(min = 32, max = 128) String claimToken,
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,127}$") String failureCode,
      boolean retryable,
      boolean recoveryConfirmed) {}

  public record RecoveryGameDayJobView(
      String gameDayId,
      String scenarioCode,
      String environment,
      Map<String, Boolean> requiredWorkerCapabilities,
      String state,
      String currentStage,
      int attempt,
      int maximumAttempts,
      int recoveryAttempt,
      int maximumRecoveryAttempts,
      String workerId,
      long claimEpoch,
      Instant availableAt,
      Instant leaseExpiresAt,
      Instant lastHeartbeatAt,
      Instant abortDeadline,
      boolean abortRequested,
      boolean faultInjected,
      Boolean recoveryConfirmed,
      String failureCode,
      String resultHash,
      Instant updatedAt) {}

  public record RecoveryGameDayJobClaimView(
      String claimToken,
      RecoveryGameDayView gameDay,
      Instant leaseExpiresAt,
      long claimEpoch,
      boolean recoveryOnly) {}

  public record RecoveryGameDayView(
      String gameDayId,
      String scenario,
      String sourceRegion,
      String targetRegion,
      String state,
      int rtoTargetSeconds,
      int rpoTargetSeconds,
      Integer observedRtoSeconds,
      Integer observedRpoSeconds,
      Integer dataLossRecords,
      String evidenceHash,
      String startedBy,
      Instant startedAt,
      Instant completedAt,
      String executionMode,
      String environment,
      RecoveryGameDayBlastRadiusRequest blastRadius,
      int maximumDurationSeconds,
      String approvalRequestId,
      String currentStage,
      boolean abortRequested,
      Boolean recoveryConfirmed,
      String failureCode,
      RecoveryGameDayJobView job) {}

  public record RecoveryGameDayEventView(
      String eventId,
      String gameDayId,
      String eventType,
      String fromState,
      String toState,
      String stage,
      String workerId,
      long claimEpoch,
      int attempt,
      String reasonCode,
      Instant occurredAt) {}

  public record RecoveryGameDayEventPage(
      List<RecoveryGameDayEventView> items, String nextCursor, boolean hasMore) {}

  public record RecoveryGameDayTrendView(
      String scenario,
      String environment,
      long totalRuns,
      long passedRuns,
      long failedRuns,
      long abortedRuns,
      long recoveryUnknownRuns,
      BigDecimal passRatePercent,
      Integer p95RtoSeconds,
      Integer p95RpoSeconds,
      long openTicketCount,
      Instant latestRunAt) {}

  public record RecoveryGameDayReportExportView(
      String exportId,
      String gameDayId,
      String reportFormat,
      int eventCount,
      Map<String, Object> report,
      String reportHash,
      String signatureAlgorithm,
      String signingKeyId,
      String signature,
      String generatedBy,
      Instant generatedAt) {}

  public record UpdateRecoveryGameDayRemediationRequest(
      @NotBlank @Pattern(regexp = "^(ACKNOWLEDGED|RESOLVED)$") String state,
      @Size(max = 128) String ownerId,
      @Size(max = 2048) String resolution) {}

  public record RecoveryGameDayRemediationView(
      String ticketId,
      String gameDayId,
      String scenario,
      String environment,
      String severity,
      String state,
      String reasonCode,
      String summary,
      String ownerId,
      String resolution,
      String createdBy,
      Instant createdAt,
      String updatedBy,
      Instant updatedAt,
      Instant resolvedAt) {}

  public record ComplianceSnapshotView(
      String snapshotId,
      String tenantId,
      String framework,
      int controlCount,
      int passingControls,
      String evidenceHash,
      Map<String, Boolean> evidence,
      String generatedBy,
      Instant generatedAt) {}

  public record EnterpriseOverviewResponse(
      List<RuntimeValidationView> validations,
      List<CostRateView> costRates,
      MediaQuotaView mediaQuota,
      ErrorBudgetView errorBudget,
      ReleaseFreezeView releaseFreeze,
      List<SlaExclusionView> slaExclusions,
      List<RetentionPolicyView> retentionPolicies,
      List<LicenseInventoryView> licenseInventory,
      List<RegionView> regions,
      List<RecoveryGameDayView> recoveryGameDays,
      List<RecoveryGameDayTrendView> recoveryGameDayTrends,
      List<RecoveryGameDayRemediationView> recoveryGameDayRemediations,
      ComplianceSnapshotView latestCompliance,
      Instant generatedAt) {}
}
