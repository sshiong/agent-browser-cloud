package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "runtime_release_requests")
public class RuntimeReleaseRequestEntity {

  @Id
  @Column(name = "release_id")
  private String releaseId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "build_id", nullable = false)
  private String buildId;

  @Column(name = "target_channel", nullable = false)
  private String targetChannel;

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

  protected RuntimeReleaseRequestEntity() {}

  public RuntimeReleaseRequestEntity(
      String releaseId,
      String tenantId,
      String buildId,
      String targetChannel,
      String reason,
      String requestedBy,
      Instant requestedAt) {
    this.releaseId = releaseId;
    this.tenantId = tenantId;
    this.buildId = buildId;
    this.targetChannel = targetChannel;
    this.reason = reason;
    this.requestedBy = requestedBy;
    this.requestedAt = requestedAt;
    this.state = "REQUESTED";
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

  public String getReleaseId() {
    return releaseId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getBuildId() {
    return buildId;
  }

  public String getTargetChannel() {
    return targetChannel;
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
