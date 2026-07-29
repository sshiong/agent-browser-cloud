package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "business_recovery_provider_evidence")
public class BusinessRecoveryProviderEvidenceEntity {

  @Id
  @Column(name = "evidence_id")
  private String evidenceId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "application_id", nullable = false)
  private String applicationId;

  @Column(name = "contract_id", nullable = false)
  private String contractId;

  @Column(name = "contract_version", nullable = false)
  private long contractVersion;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "state_version", nullable = false)
  private long stateVersion;

  @Column(name = "evidence_type", nullable = false)
  private String evidenceType;

  @Column(name = "evidence_key", nullable = false)
  private String evidenceKey;

  @Column(name = "provider_id", nullable = false)
  private String providerId;

  @Column(name = "expected_value_hash", nullable = false)
  private String expectedValueHash;

  @Column(name = "observed_value_hash", nullable = false)
  private String observedValueHash;

  @Column(name = "outcome", nullable = false)
  private String outcome;

  @Column(name = "provider_reference_hash", nullable = false)
  private String providerReferenceHash;

  @Column(name = "adapter_actor_id", nullable = false)
  private String adapterActorId;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected BusinessRecoveryProviderEvidenceEntity() {}

  public BusinessRecoveryProviderEvidenceEntity(
      String evidenceId,
      String tenantId,
      String sessionId,
      String applicationId,
      String contractId,
      long contractVersion,
      long contextEpoch,
      long stateVersion,
      String evidenceType,
      String evidenceKey,
      String providerId,
      String expectedValueHash,
      String observedValueHash,
      String outcome,
      String providerReferenceHash,
      String adapterActorId,
      String requestId,
      Instant observedAt,
      Instant expiresAt,
      Instant createdAt) {
    this.evidenceId = evidenceId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.applicationId = applicationId;
    this.contractId = contractId;
    this.contractVersion = contractVersion;
    this.contextEpoch = contextEpoch;
    this.stateVersion = stateVersion;
    this.evidenceType = evidenceType;
    this.evidenceKey = evidenceKey;
    this.providerId = providerId;
    this.expectedValueHash = expectedValueHash;
    this.observedValueHash = observedValueHash;
    this.outcome = outcome;
    this.providerReferenceHash = providerReferenceHash;
    this.adapterActorId = adapterActorId;
    this.requestId = requestId;
    this.observedAt = observedAt;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }

  public String getEvidenceId() {
    return evidenceId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getApplicationId() {
    return applicationId;
  }

  public String getContractId() {
    return contractId;
  }

  public long getContractVersion() {
    return contractVersion;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public long getStateVersion() {
    return stateVersion;
  }

  public String getEvidenceType() {
    return evidenceType;
  }

  public String getEvidenceKey() {
    return evidenceKey;
  }

  public String getProviderId() {
    return providerId;
  }

  public String getExpectedValueHash() {
    return expectedValueHash;
  }

  public String getObservedValueHash() {
    return observedValueHash;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getProviderReferenceHash() {
    return providerReferenceHash;
  }

  public String getAdapterActorId() {
    return adapterActorId;
  }

  public String getRequestId() {
    return requestId;
  }

  public Instant getObservedAt() {
    return observedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
