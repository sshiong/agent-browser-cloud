package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "application_recovery_contracts")
public class ApplicationRecoveryContractEntity {

  @Id
  @Column(name = "contract_id")
  private String contractId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "application_id", nullable = false)
  private String applicationId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "expected_origins", nullable = false, columnDefinition = "jsonb")
  private String expectedOrigins;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "ready_route_prefixes", nullable = false, columnDefinition = "jsonb")
  private String readyRoutePrefixes;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "login_route_prefixes", nullable = false, columnDefinition = "jsonb")
  private String loginRoutePrefixes;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "required_targets", nullable = false, columnDefinition = "jsonb")
  private String requiredTargets;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "login_targets", nullable = false, columnDefinition = "jsonb")
  private String loginTargets;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "permission_denied_targets", nullable = false, columnDefinition = "jsonb")
  private String permissionDeniedTargets;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "account_mismatch_targets", nullable = false, columnDefinition = "jsonb")
  private String accountMismatchTargets;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "required_extension_ids", nullable = false, columnDefinition = "jsonb")
  private String requiredExtensionIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "required_provider_evidence", nullable = false, columnDefinition = "jsonb")
  private String requiredProviderEvidence;

  @Column(name = "require_document_complete", nullable = false)
  private boolean requireDocumentComplete;

  @Column(name = "minimum_network_quiet_millis", nullable = false)
  private int minimumNetworkQuietMillis;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "transient_blocker_targets", nullable = false, columnDefinition = "jsonb")
  private String transientBlockerTargets;

  @Column(name = "allow_depth_limited", nullable = false)
  private boolean allowDepthLimited;

  @Column(name = "recovery_action", nullable = false)
  private String recoveryAction;

  @Column(name = "recovery_extension_id")
  private String recoveryExtensionId;

  @Column(name = "maximum_auto_recovery", nullable = false)
  private int maximumAutoRecovery;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ApplicationRecoveryContractEntity() {}

  /** N-1 constructor retained for tests and rolling writers that predate Provider requirements. */
  public ApplicationRecoveryContractEntity(
      String contractId,
      String tenantId,
      String applicationId,
      String expectedOrigins,
      String readyRoutePrefixes,
      String loginRoutePrefixes,
      String requiredTargets,
      String loginTargets,
      String permissionDeniedTargets,
      String accountMismatchTargets,
      String requiredExtensionIds,
      boolean allowDepthLimited,
      String recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant now) {
    this(
        contractId,
        tenantId,
        applicationId,
        expectedOrigins,
        readyRoutePrefixes,
        loginRoutePrefixes,
        requiredTargets,
        loginTargets,
        permissionDeniedTargets,
        accountMismatchTargets,
        requiredExtensionIds,
        "[]",
        allowDepthLimited,
        recoveryAction,
        recoveryExtensionId,
        maximumAutoRecovery,
        enabled,
        now);
  }

  public ApplicationRecoveryContractEntity(
      String contractId,
      String tenantId,
      String applicationId,
      String expectedOrigins,
      String readyRoutePrefixes,
      String loginRoutePrefixes,
      String requiredTargets,
      String loginTargets,
      String permissionDeniedTargets,
      String accountMismatchTargets,
      String requiredExtensionIds,
      String requiredProviderEvidence,
      boolean allowDepthLimited,
      String recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant now) {
    this(
        contractId,
        tenantId,
        applicationId,
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
        "[]",
        allowDepthLimited,
        recoveryAction,
        recoveryExtensionId,
        maximumAutoRecovery,
        enabled,
        now);
  }

  public ApplicationRecoveryContractEntity(
      String contractId,
      String tenantId,
      String applicationId,
      String expectedOrigins,
      String readyRoutePrefixes,
      String loginRoutePrefixes,
      String requiredTargets,
      String loginTargets,
      String permissionDeniedTargets,
      String accountMismatchTargets,
      String requiredExtensionIds,
      String requiredProviderEvidence,
      boolean requireDocumentComplete,
      int minimumNetworkQuietMillis,
      String transientBlockerTargets,
      boolean allowDepthLimited,
      String recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant now) {
    this.contractId = contractId;
    this.tenantId = tenantId;
    this.applicationId = applicationId;
    this.version = 1;
    apply(
        expectedOrigins,
        readyRoutePrefixes,
        loginRoutePrefixes,
        requiredTargets,
        loginTargets,
        permissionDeniedTargets,
        accountMismatchTargets,
        requiredExtensionIds,
        requiredProviderEvidence,
        requireDocumentComplete,
        minimumNetworkQuietMillis,
        transientBlockerTargets,
        allowDepthLimited,
        recoveryAction,
        recoveryExtensionId,
        maximumAutoRecovery,
        enabled,
        now);
    this.createdAt = now;
  }

  public void update(
      String expectedOrigins,
      String readyRoutePrefixes,
      String loginRoutePrefixes,
      String requiredTargets,
      String loginTargets,
      String permissionDeniedTargets,
      String accountMismatchTargets,
      String requiredExtensionIds,
      String requiredProviderEvidence,
      boolean allowDepthLimited,
      String recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant now) {
    update(
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
        "[]",
        allowDepthLimited,
        recoveryAction,
        recoveryExtensionId,
        maximumAutoRecovery,
        enabled,
        now);
  }

  public void update(
      String expectedOrigins,
      String readyRoutePrefixes,
      String loginRoutePrefixes,
      String requiredTargets,
      String loginTargets,
      String permissionDeniedTargets,
      String accountMismatchTargets,
      String requiredExtensionIds,
      String requiredProviderEvidence,
      boolean requireDocumentComplete,
      int minimumNetworkQuietMillis,
      String transientBlockerTargets,
      boolean allowDepthLimited,
      String recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant now) {
    apply(
        expectedOrigins,
        readyRoutePrefixes,
        loginRoutePrefixes,
        requiredTargets,
        loginTargets,
        permissionDeniedTargets,
        accountMismatchTargets,
        requiredExtensionIds,
        requiredProviderEvidence,
        requireDocumentComplete,
        minimumNetworkQuietMillis,
        transientBlockerTargets,
        allowDepthLimited,
        recoveryAction,
        recoveryExtensionId,
        maximumAutoRecovery,
        enabled,
        now);
  }

  /** N-1 update retained so old writers produce an empty additive Provider requirement list. */
  public void update(
      String expectedOrigins,
      String readyRoutePrefixes,
      String loginRoutePrefixes,
      String requiredTargets,
      String loginTargets,
      String permissionDeniedTargets,
      String accountMismatchTargets,
      String requiredExtensionIds,
      boolean allowDepthLimited,
      String recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant now) {
    update(
        expectedOrigins,
        readyRoutePrefixes,
        loginRoutePrefixes,
        requiredTargets,
        loginTargets,
        permissionDeniedTargets,
        accountMismatchTargets,
        requiredExtensionIds,
        "[]",
        allowDepthLimited,
        recoveryAction,
        recoveryExtensionId,
        maximumAutoRecovery,
        enabled,
        now);
  }

  private void apply(
      String expectedOrigins,
      String readyRoutePrefixes,
      String loginRoutePrefixes,
      String requiredTargets,
      String loginTargets,
      String permissionDeniedTargets,
      String accountMismatchTargets,
      String requiredExtensionIds,
      String requiredProviderEvidence,
      boolean requireDocumentComplete,
      int minimumNetworkQuietMillis,
      String transientBlockerTargets,
      boolean allowDepthLimited,
      String recoveryAction,
      String recoveryExtensionId,
      int maximumAutoRecovery,
      boolean enabled,
      Instant now) {
    this.expectedOrigins = expectedOrigins;
    this.readyRoutePrefixes = readyRoutePrefixes;
    this.loginRoutePrefixes = loginRoutePrefixes;
    this.requiredTargets = requiredTargets;
    this.loginTargets = loginTargets;
    this.permissionDeniedTargets = permissionDeniedTargets;
    this.accountMismatchTargets = accountMismatchTargets;
    this.requiredExtensionIds = requiredExtensionIds;
    this.requiredProviderEvidence = requiredProviderEvidence;
    this.requireDocumentComplete = requireDocumentComplete;
    this.minimumNetworkQuietMillis = minimumNetworkQuietMillis;
    this.transientBlockerTargets = transientBlockerTargets;
    this.allowDepthLimited = allowDepthLimited;
    this.recoveryAction = recoveryAction;
    this.recoveryExtensionId = recoveryExtensionId;
    this.maximumAutoRecovery = maximumAutoRecovery;
    this.enabled = enabled;
    this.updatedAt = now;
  }

  public String getContractId() {
    return contractId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getApplicationId() {
    return applicationId;
  }

  public long getVersion() {
    return version;
  }

  public String getExpectedOrigins() {
    return expectedOrigins;
  }

  public String getReadyRoutePrefixes() {
    return readyRoutePrefixes;
  }

  public String getLoginRoutePrefixes() {
    return loginRoutePrefixes;
  }

  public String getRequiredTargets() {
    return requiredTargets;
  }

  public String getLoginTargets() {
    return loginTargets;
  }

  public String getPermissionDeniedTargets() {
    return permissionDeniedTargets;
  }

  public String getAccountMismatchTargets() {
    return accountMismatchTargets;
  }

  public String getRequiredExtensionIds() {
    return requiredExtensionIds;
  }

  public String getRequiredProviderEvidence() {
    return requiredProviderEvidence;
  }

  public boolean isRequireDocumentComplete() {
    return requireDocumentComplete;
  }

  public int getMinimumNetworkQuietMillis() {
    return minimumNetworkQuietMillis;
  }

  public String getTransientBlockerTargets() {
    return transientBlockerTargets;
  }

  public boolean isAllowDepthLimited() {
    return allowDepthLimited;
  }

  public String getRecoveryAction() {
    return recoveryAction;
  }

  public String getRecoveryExtensionId() {
    return recoveryExtensionId;
  }

  public int getMaximumAutoRecovery() {
    return maximumAutoRecovery;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
