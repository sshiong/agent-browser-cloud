package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "runtime_builds")
public class RuntimeBuildEntity {

  @Id
  @Column(name = "build_id")
  private String buildId;

  @Column(nullable = false)
  private String engine;

  @Column(nullable = false)
  private String version;

  @Column(nullable = false)
  private String platform;

  @Column(name = "security_tier", nullable = false)
  private String securityTier;

  private String signature;

  @Column(name = "sbom_url")
  private String sbomUrl;

  @Column(name = "regression_status")
  private String regressionStatus;

  @Column(name = "validated_at")
  private Instant validatedAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  @Column(name = "release_channel", nullable = false)
  private String releaseChannel;

  @Column(name = "disabled_at")
  private Instant disabledAt;

  @Column(name = "disabled_by")
  private String disabledBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getBuildId() {
    return buildId;
  }

  public String getEngine() {
    return engine;
  }

  public String getVersion() {
    return version;
  }

  public String getPlatform() {
    return platform;
  }

  public String getSecurityTier() {
    return securityTier;
  }

  public String getSignature() {
    return signature;
  }

  public String getSbomUrl() {
    return sbomUrl;
  }

  public String getRegressionStatus() {
    return regressionStatus;
  }

  public Instant getValidatedAt() {
    return validatedAt;
  }

  public Instant getReleasedAt() {
    return releasedAt;
  }

  public String getReleaseChannel() {
    return releaseChannel;
  }

  public Instant getDisabledAt() {
    return disabledAt;
  }

  public String getDisabledBy() {
    return disabledBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void release(String channel, Instant now) {
    releaseChannel = channel;
    regressionStatus = channel;
    releasedAt = now;
    disabledAt = null;
    disabledBy = null;
  }

  public void disable(String actorId, Instant now) {
    releaseChannel = "DISABLED";
    regressionStatus = "DISABLED";
    disabledAt = now;
    disabledBy = actorId;
  }
}
