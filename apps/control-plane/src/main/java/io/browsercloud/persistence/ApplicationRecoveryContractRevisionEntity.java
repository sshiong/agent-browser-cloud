package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@IdClass(ApplicationRecoveryContractRevisionId.class)
@Table(name = "application_recovery_contract_revisions")
public class ApplicationRecoveryContractRevisionEntity {

  @Id
  @Column(name = "contract_id")
  private String contractId;

  @Id
  @Column(name = "contract_version")
  private long contractVersion;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "application_id", nullable = false)
  private String applicationId;

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

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payment_security_route_prefixes", nullable = false, columnDefinition = "jsonb")
  private String paymentSecurityRoutePrefixes;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(
      name = "critical_transaction_route_prefixes",
      nullable = false,
      columnDefinition = "jsonb")
  private String criticalTransactionRoutePrefixes;

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

  @Column(name = "contract_created_at", nullable = false)
  private Instant contractCreatedAt;

  @Column(name = "published_at", nullable = false)
  private Instant publishedAt;

  protected ApplicationRecoveryContractRevisionEntity() {}

  public String getContractId() {
    return contractId;
  }

  public long getContractVersion() {
    return contractVersion;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getApplicationId() {
    return applicationId;
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

  public String getPaymentSecurityRoutePrefixes() {
    return paymentSecurityRoutePrefixes;
  }

  public String getCriticalTransactionRoutePrefixes() {
    return criticalTransactionRoutePrefixes;
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

  public Instant getContractCreatedAt() {
    return contractCreatedAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }
}
