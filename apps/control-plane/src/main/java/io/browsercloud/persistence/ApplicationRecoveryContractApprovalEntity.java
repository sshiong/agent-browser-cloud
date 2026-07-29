package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "application_recovery_contract_approvals")
public class ApplicationRecoveryContractApprovalEntity {

  @Id
  @Column(name = "approval_id")
  private String approvalId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "contract_id", nullable = false)
  private String contractId;

  @Column(name = "application_id", nullable = false)
  private String applicationId;

  @Column(name = "contract_version", nullable = false)
  private long contractVersion;

  @Column(nullable = false)
  private String reason;

  @Column(nullable = false)
  private String state;

  @Column(name = "requested_by", nullable = false)
  private String requestedBy;

  @Column(name = "approved_by")
  private String approvedBy;

  @Column(name = "rejected_by")
  private String rejectedBy;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "evidence_hash")
  private String evidenceHash;

  protected ApplicationRecoveryContractApprovalEntity() {}

  public ApplicationRecoveryContractApprovalEntity(
      String approvalId,
      String tenantId,
      String contractId,
      String applicationId,
      long contractVersion,
      String reason,
      String requestedBy,
      Instant requestedAt) {
    this.approvalId = approvalId;
    this.tenantId = tenantId;
    this.contractId = contractId;
    this.applicationId = applicationId;
    this.contractVersion = contractVersion;
    this.reason = reason;
    this.state = "REQUESTED";
    this.requestedBy = requestedBy;
    this.requestedAt = requestedAt;
  }

  public void approve(String actorId, String evidenceHash, Instant now) {
    state = "APPROVED";
    approvedBy = actorId;
    this.evidenceHash = evidenceHash;
    decidedAt = now;
  }

  public void reject(String actorId, Instant now) {
    state = "REJECTED";
    rejectedBy = actorId;
    decidedAt = now;
  }

  public String getApprovalId() {
    return approvalId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getContractId() {
    return contractId;
  }

  public String getApplicationId() {
    return applicationId;
  }

  public long getContractVersion() {
    return contractVersion;
  }

  public String getReason() {
    return reason;
  }

  public String getState() {
    return state;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public String getApprovedBy() {
    return approvedBy;
  }

  public String getRejectedBy() {
    return rejectedBy;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public String getEvidenceHash() {
    return evidenceHash;
  }
}
