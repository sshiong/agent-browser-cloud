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

  public enum RecoveryContractApprovalState {
    DRAFT,
    REQUESTED,
    APPROVED,
    REJECTED
  }

  public enum ProviderEvidenceType {
    ACCOUNT,
    TENANT_WORKSPACE,
    PERMISSION,
    BUSINESS_ENTITY
  }

  public enum ProviderEvidenceOutcome {
    MATCH,
    MISMATCH,
    UNKNOWN
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
      @Valid @Size(max = 16) List<@NotNull ProviderEvidenceRequirement> requiredProviderEvidence,
      boolean requireDocumentComplete,
      @Min(0) @Max(30000) int minimumNetworkQuietMillis,
      @Valid @Size(max = 32) List<@NotNull TargetIndicator> transientBlockerTargets,
      @Size(max = 32) List<@NotBlank @Size(max = 512) String> paymentSecurityRoutePrefixes,
      @Size(max = 32) List<@NotBlank @Size(max = 512) String> criticalTransactionRoutePrefixes,
      boolean allowDepthLimited,
      RecoveryAction recoveryAction,
      @Pattern(regexp = "^[a-p]{32}$") String recoveryExtensionId,
      @Min(0) @Max(10) int maximumAutoRecovery,
      boolean enabled) {

    /** N-1 source compatibility: absence of this additive field means no Provider requirement. */
    public UpsertRecoveryContractRequest(
        long expectedVersion,
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
        boolean enabled) {
      this(
          expectedVersion,
          expectedOrigins,
          readyRoutePrefixes,
          loginRoutePrefixes,
          requiredTargets,
          loginTargets,
          permissionDeniedTargets,
          accountMismatchTargets,
          requiredExtensionIds,
          List.of(),
          false,
          0,
          List.of(),
          List.of(),
          List.of(),
          allowDepthLimited,
          recoveryAction,
          recoveryExtensionId,
          maximumAutoRecovery,
          enabled);
    }

    /** V052 source compatibility: Provider requirements without Browser readiness rules. */
    public UpsertRecoveryContractRequest(
        long expectedVersion,
        List<String> expectedOrigins,
        List<String> readyRoutePrefixes,
        List<String> loginRoutePrefixes,
        List<TargetIndicator> requiredTargets,
        List<TargetIndicator> loginTargets,
        List<TargetIndicator> permissionDeniedTargets,
        List<TargetIndicator> accountMismatchTargets,
        List<String> requiredExtensionIds,
        List<ProviderEvidenceRequirement> requiredProviderEvidence,
        boolean allowDepthLimited,
        RecoveryAction recoveryAction,
        String recoveryExtensionId,
        int maximumAutoRecovery,
        boolean enabled) {
      this(
          expectedVersion,
          expectedOrigins,
          readyRoutePrefixes,
          loginRoutePrefixes,
          requiredTargets,
          loginTargets,
          permissionDeniedTargets,
          accountMismatchTargets,
          requiredExtensionIds,
          requiredProviderEvidence,
          false,
          0,
          List.of(),
          List.of(),
          List.of(),
          allowDepthLimited,
          recoveryAction,
          recoveryExtensionId,
          maximumAutoRecovery,
          enabled);
    }
  }

  public record ProviderEvidenceRequirement(
      @NotNull ProviderEvidenceType type,
      @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String key,
      @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String providerId,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedValueHash,
      @Min(30) @Max(900) int maxAgeSeconds) {}

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
      List<ProviderEvidenceRequirement> requiredProviderEvidence,
      boolean requireDocumentComplete,
      int minimumNetworkQuietMillis,
      List<TargetIndicator> transientBlockerTargets,
      List<String> paymentSecurityRoutePrefixes,
      List<String> criticalTransactionRoutePrefixes,
      boolean allowDepthLimited,
      RecoveryAction recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      RecoveryContractApprovalState approvalState,
      String approvalId,
      String approvalRequestedBy,
      String approvedBy,
      Instant approvalRequestedAt,
      Instant approvalDecidedAt,
      Instant createdAt,
      Instant updatedAt) {}

  public record SubmitProviderEvidenceRequest(
      @Min(1) long contextEpoch,
      @Min(1) long stateVersion,
      @NotNull ProviderEvidenceType type,
      @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String key,
      @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String providerId,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String observedValueHash,
      @NotNull ProviderEvidenceOutcome outcome,
      @NotBlank @Size(max = 512) String providerReference,
      @NotNull Instant observedAt) {}

  public record ProviderEvidenceView(
      String evidenceId,
      String sessionId,
      String applicationId,
      long contractVersion,
      long contextEpoch,
      long stateVersion,
      ProviderEvidenceType type,
      String key,
      String providerId,
      ProviderEvidenceOutcome outcome,
      boolean valueHashMatched,
      String providerReferenceHash,
      String adapterActorId,
      String requestId,
      Instant observedAt,
      Instant expiresAt,
      Instant createdAt) {}

  public record ProviderEvidenceListResponse(List<ProviderEvidenceView> items, long total) {}

  public record RecoveryContractListResponse(List<RecoveryContractView> items, long total) {}

  public record RecoveryContractRevisionListResponse(
      List<RecoveryContractView> items, long total, long currentVersion) {}

  public record RecoveryContractFieldChange(
      String field, String changeType, String beforeValue, String afterValue) {}

  public record RecoveryContractDiffView(
      String contractId,
      String applicationId,
      long fromVersion,
      long toVersion,
      List<RecoveryContractFieldChange> changes,
      long total) {}

  public record RestoreRecoveryContractRevisionRequest(
      @Min(1) long expectedCurrentVersion,
      @Min(1) long sourceContractVersion,
      @NotBlank @Size(max = 500) String reason) {}

  public record RequestRecoveryContractApprovalRequest(
      @Min(1) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

  public record RecoveryContractApprovalView(
      String approvalId,
      String contractId,
      String applicationId,
      long contractVersion,
      String reason,
      RecoveryContractApprovalState state,
      String requestedBy,
      String approvedBy,
      String rejectedBy,
      Instant requestedAt,
      Instant decidedAt,
      String evidenceHash) {}

  public record SessionApplicationBindingView(
      String sessionId,
      String applicationId,
      String contractId,
      long contractVersion,
      long latestContractVersion,
      RecoveryContractApprovalState latestApprovalState,
      boolean currentContractEnabled,
      boolean upgradeAvailable,
      Instant boundAt) {}

  public record RebindSessionApplicationRequest(
      @Min(1) long expectedCurrentVersion, @Min(1) long targetContractVersion) {}

  public record SessionApplicationRebindView(
      String operationId,
      String sessionId,
      String applicationId,
      String contractId,
      long previousContractVersion,
      long targetContractVersion,
      String state,
      String requestId,
      Instant createdAt,
      Instant completedAt) {}

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
