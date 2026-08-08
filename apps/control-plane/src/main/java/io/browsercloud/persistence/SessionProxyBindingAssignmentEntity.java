package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "session_proxy_binding_assignments")
public class SessionProxyBindingAssignmentEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "binding_profile_id", nullable = false)
  private String bindingProfileId;

  @Column(name = "binding_version", nullable = false)
  private long bindingVersion;

  @Column(name = "provider_id", nullable = false)
  private String providerId;

  private String region;

  @Column(name = "expected_exit_ip", nullable = false)
  private String expectedExitIp;

  @Column(name = "credential_ref", nullable = false)
  private String credentialRef;

  @Column(name = "assigned_by", nullable = false)
  private String assignedBy;

  @Column(name = "assigned_at", nullable = false)
  private Instant assignedAt;

  @Column(name = "selection_mode", nullable = false)
  private String selectionMode;

  @Column(name = "routing_score")
  private BigDecimal routingScore;

  @Column(name = "quality_score")
  private Integer qualityScore;

  @Column(name = "reputation_score")
  private Integer reputationScore;

  @Column(name = "cost_per_gib_usd")
  private BigDecimal costPerGibUsd;

  @Column(name = "active_reservations")
  private Integer activeReservations;

  @Column(name = "max_concurrent_sessions")
  private Integer maxConcurrentSessions;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "candidate_scores", columnDefinition = "jsonb")
  private List<Map<String, Object>> candidateScores;

  protected SessionProxyBindingAssignmentEntity() {}

  public SessionProxyBindingAssignmentEntity(
      String sessionId,
      String tenantId,
      String bindingProfileId,
      long bindingVersion,
      String providerId,
      String region,
      String expectedExitIp,
      String credentialRef,
      String assignedBy,
      Instant assignedAt) {
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.bindingProfileId = bindingProfileId;
    this.bindingVersion = bindingVersion;
    this.providerId = providerId;
    this.region = region;
    this.expectedExitIp = expectedExitIp;
    this.credentialRef = credentialRef;
    this.assignedBy = assignedBy;
    this.assignedAt = assignedAt;
    this.selectionMode = "EXPLICIT";
  }

  public static SessionProxyBindingAssignmentEntity automatic(
      String sessionId,
      String tenantId,
      String bindingProfileId,
      long bindingVersion,
      String providerId,
      String region,
      String expectedExitIp,
      String credentialRef,
      String assignedBy,
      Instant assignedAt,
      double routingScore,
      int qualityScore,
      int reputationScore,
      BigDecimal costPerGibUsd,
      int activeReservations,
      int maxConcurrentSessions,
      List<Map<String, Object>> candidateScores) {
    var entity =
        new SessionProxyBindingAssignmentEntity(
            sessionId,
            tenantId,
            bindingProfileId,
            bindingVersion,
            providerId,
            region,
            expectedExitIp,
            credentialRef,
            assignedBy,
            assignedAt);
    entity.selectionMode = "AUTO";
    entity.routingScore =
        BigDecimal.valueOf(routingScore).setScale(3, java.math.RoundingMode.HALF_UP);
    entity.qualityScore = qualityScore;
    entity.reputationScore = reputationScore;
    entity.costPerGibUsd = costPerGibUsd;
    entity.activeReservations = activeReservations;
    entity.maxConcurrentSessions = maxConcurrentSessions;
    entity.candidateScores = List.copyOf(candidateScores);
    return entity;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getBindingProfileId() {
    return bindingProfileId;
  }

  public long getBindingVersion() {
    return bindingVersion;
  }

  public String getProviderId() {
    return providerId;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }

  public String getRegion() {
    return region;
  }

  public String getExpectedExitIp() {
    return expectedExitIp;
  }

  public String getCredentialRef() {
    return credentialRef;
  }

  public String getSelectionMode() {
    return selectionMode;
  }

  public Double getRoutingScore() {
    return routingScore == null ? null : routingScore.doubleValue();
  }

  public Integer getQualityScore() {
    return qualityScore;
  }

  public Integer getReputationScore() {
    return reputationScore;
  }

  public BigDecimal getCostPerGibUsd() {
    return costPerGibUsd;
  }

  public Integer getActiveReservations() {
    return activeReservations;
  }

  public Integer getMaxConcurrentSessions() {
    return maxConcurrentSessions;
  }

  public List<Map<String, Object>> getCandidateScores() {
    return candidateScores == null ? null : List.copyOf(candidateScores);
  }
}
