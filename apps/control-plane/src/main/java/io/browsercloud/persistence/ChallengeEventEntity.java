package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Input-free challenge classification bound to one authoritative Browser State. */
@Entity
@Table(name = "challenge_events")
public class ChallengeEventEntity {

  @Id
  @Column(name = "challenge_event_id")
  private String challengeEventId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "state_version", nullable = false)
  private long stateVersion;

  @Column(name = "target_revision", nullable = false)
  private long targetRevision;

  @Column(nullable = false)
  private double confidence;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String evidence;

  @Column(name = "suspected_type", nullable = false)
  private String suspectedType;

  @Column(name = "access_outcome", nullable = false)
  private String accessOutcome;

  @Column(name = "target_ref")
  private String targetRef;

  @Column(name = "target_summary", nullable = false)
  private String targetSummary;

  @Column(name = "visual_anchor_hash")
  private String visualAnchorHash;

  @Column(nullable = false)
  private String status;

  @Column(name = "detected_at", nullable = false)
  private Instant detectedAt;

  @Column(name = "authorization_deadline", nullable = false)
  private Instant authorizationDeadline;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected ChallengeEventEntity() {}

  public ChallengeEventEntity(
      String challengeEventId,
      String tenantId,
      String sessionId,
      long contextEpoch,
      long stateVersion,
      long targetRevision,
      double confidence,
      String evidence,
      String suspectedType,
      String accessOutcome,
      String targetRef,
      String targetSummary,
      String visualAnchorHash,
      String status,
      Instant detectedAt,
      Instant authorizationDeadline,
      Instant expiresAt) {
    this.challengeEventId = challengeEventId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.contextEpoch = contextEpoch;
    this.stateVersion = stateVersion;
    this.targetRevision = targetRevision;
    this.confidence = confidence;
    this.evidence = evidence;
    this.suspectedType = suspectedType;
    this.accessOutcome = accessOutcome;
    this.targetRef = targetRef;
    this.targetSummary = targetSummary;
    this.visualAnchorHash = visualAnchorHash;
    this.status = status;
    this.detectedAt = detectedAt;
    this.authorizationDeadline = authorizationDeadline;
    this.expiresAt = expiresAt;
    this.updatedAt = detectedAt;
  }

  public void authorize(Instant now) {
    requireStatus("CONFIRMED");
    status = "AUTHORIZED";
    updatedAt = now;
  }

  public void executing(Instant now) {
    if (!"AUTHORIZED".equals(status)) return;
    status = "EXECUTING";
    updatedAt = now;
  }

  public void inputExecuting(Instant now) {
    if (!java.util.Set.of("TAKEOVER_REQUIRED", "CONFIRMED").contains(status)) {
      throw new IllegalStateException("challenge event is not waiting for an input response");
    }
    status = "EXECUTING";
    updatedAt = now;
  }

  public void inputAttemptFailed(Instant now) {
    requireStatus("EXECUTING");
    status = "TAKEOVER_REQUIRED";
    updatedAt = now;
  }

  public void resolved(Instant now) {
    if ("RESOLVED".equals(status)) return;
    requireStatus("EXECUTING");
    status = "RESOLVED";
    updatedAt = now;
  }

  public void resolvedByHumanTakeover(Instant now) {
    if ("RESOLVED".equals(status)) return;
    if (!java.util.Set.of("TAKEOVER_REQUIRED", "CONFIRMED", "SUPERSEDED").contains(status)) {
      throw new IllegalStateException("challenge event is not waiting for human takeover");
    }
    status = "RESOLVED";
    updatedAt = now;
  }

  public void failed(Instant now) {
    if ("FAILED".equals(status)) return;
    if (!"AUTHORIZED".equals(status) && !"EXECUTING".equals(status)) {
      throw new IllegalStateException("challenge event is not executing");
    }
    status = "FAILED";
    updatedAt = now;
  }

  public void supersede(Instant now) {
    if (isTerminal()) return;
    status = "SUPERSEDED";
    updatedAt = now;
  }

  public void expire(Instant now) {
    if (isTerminal()) return;
    status = "EXPIRED";
    updatedAt = now;
  }

  public boolean isTerminal() {
    return java.util.Set.of("RESOLVED", "FAILED", "EXPIRED", "SUPERSEDED").contains(status);
  }

  private void requireStatus(String expected) {
    if (!expected.equals(status)) {
      throw new IllegalStateException("challenge event state is " + status);
    }
  }

  public String getChallengeEventId() {
    return challengeEventId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public long getStateVersion() {
    return stateVersion;
  }

  public long getTargetRevision() {
    return targetRevision;
  }

  public double getConfidence() {
    return confidence;
  }

  public String getEvidence() {
    return evidence;
  }

  public String getSuspectedType() {
    return suspectedType;
  }

  public String getAccessOutcome() {
    return accessOutcome;
  }

  public String getTargetRef() {
    return targetRef;
  }

  public String getTargetSummary() {
    return targetSummary;
  }

  public String getVisualAnchorHash() {
    return visualAnchorHash;
  }

  public String getStatus() {
    return status;
  }

  public Instant getDetectedAt() {
    return detectedAt;
  }

  public Instant getAuthorizationDeadline() {
    return authorizationDeadline;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
