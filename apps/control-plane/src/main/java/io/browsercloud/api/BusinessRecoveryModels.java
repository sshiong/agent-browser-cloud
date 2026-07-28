package io.browsercloud.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Tenant-owned declarative Business Recovery contracts and durable validation views. */
public final class BusinessRecoveryModels {

  private BusinessRecoveryModels() {}

  public enum Verdict {
    READY,
    READY_WITH_WARNING,
    LOGIN_REQUIRED,
    PERMISSION_CHANGED,
    ACCOUNT_MISMATCH,
    APPLICATION_UNAVAILABLE,
    STATE_CHANGED,
    MANUAL_RECOVERY_REQUIRED
  }

  public enum RecoveryAction {
    NONE,
    RELOAD,
    NAVIGATE_HOME,
    REOPEN_KNOWN_ROUTE,
    REFRESH_SESSION,
    RESTART_EXTENSION
  }

  public record TargetIndicator(
      @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]{0,63}$") String role,
      @NotBlank @Size(max = 160) String name) {}

  public record UpsertRecoveryContractRequest(
      @Min(0) long expectedVersion,
      @Size(min = 1, max = 16) List<@NotBlank @Size(max = 512) String> expectedOrigins,
      @Size(max = 32) List<@NotBlank @Size(max = 512) String> readyRoutePrefixes,
      @Size(max = 32) List<@NotBlank @Size(max = 512) String> loginRoutePrefixes,
      @Valid @Size(max = 32) List<@NotNull TargetIndicator> requiredTargets,
      @Valid @Size(max = 32) List<@NotNull TargetIndicator> loginTargets,
      @Valid @Size(max = 32) List<@NotNull TargetIndicator> permissionDeniedTargets,
      @Valid @Size(max = 32) List<@NotNull TargetIndicator> accountMismatchTargets,
      @Size(max = 32)
          List<@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String> requiredExtensionIds,
      boolean allowDepthLimited,
      RecoveryAction recoveryAction,
      @Pattern(regexp = "^[a-p]{32}$") String recoveryExtensionId,
      @Min(0) @Max(10) int maximumAutoRecovery,
      boolean enabled) {}

  public record RecoveryContractView(
      String contractId,
      String applicationId,
      long version,
      List<String> expectedOrigins,
      List<String> readyRoutePrefixes,
      List<String> loginRoutePrefixes,
      List<TargetIndicator> requiredTargets,
      List<TargetIndicator> loginTargets,
      List<TargetIndicator> permissionDeniedTargets,
      List<TargetIndicator> accountMismatchTargets,
      List<String> requiredExtensionIds,
      boolean allowDepthLimited,
      RecoveryAction recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt) {}

  public record RecoveryContractListResponse(List<RecoveryContractView> items, long total) {}

  public record BusinessRecoveryValidationView(
      String validationId,
      String sessionId,
      String applicationId,
      Long contractVersion,
      long contextEpoch,
      long stateVersion,
      Verdict verdict,
      boolean ready,
      List<String> evidence,
      String source,
      String requestId,
      Instant evaluatedAt) {}

  public record BusinessRecoveryActionView(
      String actionId,
      String migrationId,
      int attemptNumber,
      RecoveryAction action,
      String targetUrl,
      String targetExtensionId,
      long baseStateVersion,
      Long resultingStateVersion,
      String state,
      String errorCode,
      Instant createdAt,
      Instant completedAt) {}
}
