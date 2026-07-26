package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "key_rotation_requests")
public class KeyRotationRequestEntity {

  @Id
  @Column(name = "rotation_id")
  private String rotationId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "key_scope", nullable = false)
  private String keyScope;

  @Column(name = "old_key_id", nullable = false)
  private String oldKeyId;

  @Column(name = "new_key_id", nullable = false)
  private String newKeyId;

  @Column(name = "rotation_trigger", nullable = false)
  private String rotationTrigger;

  @Column(nullable = false)
  private String reason;

  @Column(name = "requested_overlap_minutes", nullable = false)
  private int requestedOverlapMinutes;

  @Column(nullable = false)
  private String state;

  @Column(name = "requested_by", nullable = false)
  private String requestedBy;

  @Column(name = "approved_by")
  private String approvedBy;

  @Column(name = "completed_by")
  private String completedBy;

  @Column(name = "revoked_by")
  private String revokedBy;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "overlap_until")
  private Instant overlapUntil;

  @Column(name = "progress_percent", nullable = false)
  private int progressPercent;

  @Column(name = "new_key_write_verified")
  private Boolean newKeyWriteVerified;

  @Column(name = "old_key_read_verified")
  private Boolean oldKeyReadVerified;

  @Column(name = "plaintext_rejected")
  private Boolean plaintextRejected;

  @Column(name = "affected_workloads")
  private Integer affectedWorkloads;

  @Column(name = "verification_reference")
  private String verificationReference;

  @Column(name = "approval_evidence_hash")
  private String approvalEvidenceHash;

  @Column(name = "completion_evidence_hash")
  private String completionEvidenceHash;

  protected KeyRotationRequestEntity() {}

  public KeyRotationRequestEntity(
      String rotationId,
      String tenantId,
      String keyScope,
      String oldKeyId,
      String newKeyId,
      String rotationTrigger,
      String reason,
      int requestedOverlapMinutes,
      String requestedBy,
      Instant requestedAt) {
    this.rotationId = rotationId;
    this.tenantId = tenantId;
    this.keyScope = keyScope;
    this.oldKeyId = oldKeyId;
    this.newKeyId = newKeyId;
    this.rotationTrigger = rotationTrigger;
    this.reason = reason;
    this.requestedOverlapMinutes = requestedOverlapMinutes;
    this.requestedBy = requestedBy;
    this.requestedAt = requestedAt;
    state = "REQUESTED";
  }

  public void approve(String actorId, Instant now, Instant overlapUntil, String evidenceHash) {
    state = "ROTATING";
    approvedBy = actorId;
    approvedAt = now;
    startedAt = now;
    this.overlapUntil = overlapUntil;
    approvalEvidenceHash = evidenceHash;
    progressPercent = 1;
  }

  public void complete(
      String actorId,
      Instant now,
      boolean newKeyWriteVerified,
      boolean oldKeyReadVerified,
      boolean plaintextRejected,
      int affectedWorkloads,
      String verificationReference,
      String evidenceHash) {
    state = "COMPLETED";
    completedBy = actorId;
    completedAt = now;
    progressPercent = 100;
    this.newKeyWriteVerified = newKeyWriteVerified;
    this.oldKeyReadVerified = oldKeyReadVerified;
    this.plaintextRejected = plaintextRejected;
    this.affectedWorkloads = affectedWorkloads;
    this.verificationReference = verificationReference;
    completionEvidenceHash = evidenceHash;
  }

  public void revoke(String actorId, Instant now) {
    state = "REVOKED";
    revokedBy = actorId;
    revokedAt = now;
  }

  public String getRotationId() {
    return rotationId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getKeyScope() {
    return keyScope;
  }

  public String getOldKeyId() {
    return oldKeyId;
  }

  public String getNewKeyId() {
    return newKeyId;
  }

  public String getRotationTrigger() {
    return rotationTrigger;
  }

  public String getReason() {
    return reason;
  }

  public int getRequestedOverlapMinutes() {
    return requestedOverlapMinutes;
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

  public String getCompletedBy() {
    return completedBy;
  }

  public String getRevokedBy() {
    return revokedBy;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public Instant getOverlapUntil() {
    return overlapUntil;
  }

  public int getProgressPercent() {
    return progressPercent;
  }

  public Boolean getNewKeyWriteVerified() {
    return newKeyWriteVerified;
  }

  public Boolean getOldKeyReadVerified() {
    return oldKeyReadVerified;
  }

  public Boolean getPlaintextRejected() {
    return plaintextRejected;
  }

  public Integer getAffectedWorkloads() {
    return affectedWorkloads;
  }

  public String getVerificationReference() {
    return verificationReference;
  }

  public String getApprovalEvidenceHash() {
    return approvalEvidenceHash;
  }

  public String getCompletionEvidenceHash() {
    return completionEvidenceHash;
  }
}
