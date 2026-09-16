package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "profile_site_session_health")
public class ProfileSiteSessionHealthEntity {

  @Id
  @Column(name = "health_id")
  private String healthId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "profile_id", nullable = false)
  private String profileId;

  @Column(name = "site_origin", nullable = false)
  private String siteOrigin;

  @Column(name = "application_id")
  private String applicationId;

  @Column(name = "health_state", nullable = false)
  private String healthState;

  @Column(name = "reason_code", nullable = false)
  private String reasonCode;

  @Column(name = "source_session_id")
  private String sourceSessionId;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "state_version", nullable = false)
  private long stateVersion;

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;

  @Column(name = "fresh_until", nullable = false)
  private Instant freshUntil;

  @Column(name = "authenticated_at")
  private Instant authenticatedAt;

  @Column(name = "reauth_required_at")
  private Instant reauthRequiredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ProfileSiteSessionHealthEntity() {}

  public ProfileSiteSessionHealthEntity(
      String healthId, String tenantId, String profileId, String siteOrigin, Instant now) {
    this.healthId = healthId;
    this.tenantId = tenantId;
    this.profileId = profileId;
    this.siteOrigin = siteOrigin;
    this.healthState = "DEGRADED";
    this.reasonCode = "AWAITING_OBSERVATION";
    this.contextEpoch = 0;
    this.stateVersion = 0;
    this.checkedAt = now;
    this.freshUntil = now;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void observe(
      String applicationId,
      String healthState,
      String reasonCode,
      String sourceSessionId,
      long contextEpoch,
      long stateVersion,
      Instant checkedAt,
      Instant freshUntil) {
    this.applicationId = applicationId;
    this.healthState = healthState;
    this.reasonCode = reasonCode;
    this.sourceSessionId = sourceSessionId;
    this.contextEpoch = contextEpoch;
    this.stateVersion = stateVersion;
    this.checkedAt = checkedAt;
    this.freshUntil = freshUntil;
    if ("HEALTHY".equals(healthState)) {
      this.authenticatedAt = checkedAt;
      this.reauthRequiredAt = null;
    } else if ("REAUTH_REQUIRED".equals(healthState)) {
      this.reauthRequiredAt = checkedAt;
    }
    this.updatedAt = checkedAt;
  }

  public String getHealthId() {
    return healthId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getProfileId() {
    return profileId;
  }

  public String getSiteOrigin() {
    return siteOrigin;
  }

  public String getApplicationId() {
    return applicationId;
  }

  public String getHealthState() {
    return healthState;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public String getSourceSessionId() {
    return sourceSessionId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public long getStateVersion() {
    return stateVersion;
  }

  public Instant getCheckedAt() {
    return checkedAt;
  }

  public Instant getFreshUntil() {
    return freshUntil;
  }

  public Instant getAuthenticatedAt() {
    return authenticatedAt;
  }

  public Instant getReauthRequiredAt() {
    return reauthRequiredAt;
  }
}
