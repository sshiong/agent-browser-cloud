package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "break_glass_requests")
public class BreakGlassRequestEntity {

  @Id
  @Column(name = "request_id")
  private String requestId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "ticket_id", nullable = false)
  private String ticketId;

  @Column(nullable = false)
  private String reason;

  @Column(name = "resource_type", nullable = false)
  private String resourceType;

  @Column(name = "resource_id", nullable = false)
  private String resourceId;

  @Column(name = "requested_scope", nullable = false)
  private String requestedScope;

  @Column(nullable = false)
  private String state;

  @Column(name = "requested_by", nullable = false)
  private String requestedBy;

  @Column(name = "approved_by")
  private String approvedBy;

  @Column(name = "rejected_by")
  private String rejectedBy;

  @Column(name = "revoked_by")
  private String revokedBy;

  @Column(name = "evidence_hash")
  private String evidenceHash;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  protected BreakGlassRequestEntity() {}

  public BreakGlassRequestEntity(
      String requestId,
      String tenantId,
      String ticketId,
      String reason,
      String resourceType,
      String resourceId,
      String requestedScope,
      String requestedBy,
      Instant requestedAt,
      Instant expiresAt) {
    this.requestId = requestId;
    this.tenantId = tenantId;
    this.ticketId = ticketId;
    this.reason = reason;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
    this.requestedScope = requestedScope;
    this.requestedBy = requestedBy;
    this.requestedAt = requestedAt;
    this.expiresAt = expiresAt;
    this.state = "REQUESTED";
  }

  public void approve(String actorId, String evidenceHash, Instant now) {
    state = "ACTIVE";
    approvedBy = actorId;
    approvedAt = now;
    this.evidenceHash = evidenceHash;
  }

  public void reject(String actorId, Instant now) {
    state = "REJECTED";
    rejectedBy = actorId;
    rejectedAt = now;
  }

  public void revoke(String actorId, Instant now) {
    state = "REVOKED";
    revokedBy = actorId;
    revokedAt = now;
  }

  public void expire(Instant now) {
    state = "EXPIRED";
    revokedBy = "system";
    revokedAt = now;
  }

  public void markReviewed(Instant now) {
    reviewedAt = now;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getTicketId() {
    return ticketId;
  }

  public String getReason() {
    return reason;
  }

  public String getResourceType() {
    return resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public String getRequestedScope() {
    return requestedScope;
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

  public String getRevokedBy() {
    return revokedBy;
  }

  public String getEvidenceHash() {
    return evidenceHash;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public Instant getRejectedAt() {
    return rejectedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }
}
