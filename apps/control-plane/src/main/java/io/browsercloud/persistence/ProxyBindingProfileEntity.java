package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "proxy_binding_profiles")
public class ProxyBindingProfileEntity {

  @Id
  @Column(name = "binding_profile_id")
  private String bindingProfileId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String name;

  private String description;

  @Column(name = "provider_id", nullable = false)
  private String providerId;

  private String region;

  @Column(name = "expected_exit_ip", nullable = false)
  private String expectedExitIp;

  @Column(name = "credential_ref", nullable = false)
  private String credentialRef;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "health_state", nullable = false)
  private String healthState;

  @Column(name = "last_verified_exit_ip")
  private String lastVerifiedExitIp;

  @Column(name = "last_health_checked_at")
  private Instant lastHealthCheckedAt;

  @Column(name = "last_failure_reason")
  private String lastFailureReason;

  @Column(name = "probe_success_count", nullable = false)
  private long probeSuccessCount;

  @Column(name = "probe_failure_count", nullable = false)
  private long probeFailureCount;

  @Column(name = "consecutive_probe_successes", nullable = false)
  private int consecutiveProbeSuccesses;

  @Column(name = "consecutive_probe_failures", nullable = false)
  private int consecutiveProbeFailures;

  @Column(name = "probe_success_ewma")
  private BigDecimal probeSuccessEwma;

  @Column(name = "probe_latency_ewma_ms")
  private BigDecimal probeLatencyEwmaMs;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected ProxyBindingProfileEntity() {}

  public ProxyBindingProfileEntity(
      String bindingProfileId,
      String tenantId,
      String name,
      String description,
      String providerId,
      String region,
      String expectedExitIp,
      String credentialRef,
      boolean enabled,
      String createdBy,
      Instant now) {
    this.bindingProfileId = bindingProfileId;
    this.tenantId = tenantId;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.healthState = enabled ? "UNVERIFIED" : "DISABLED";
    update(name, description, providerId, region, expectedExitIp, credentialRef, enabled, now);
  }

  public void update(
      String name,
      String description,
      String providerId,
      String region,
      String expectedExitIp,
      String credentialRef,
      boolean enabled,
      Instant now) {
    this.name = name.strip();
    this.description = blankToNull(description);
    this.providerId = providerId;
    this.region = blankToNull(region);
    this.expectedExitIp = expectedExitIp.strip();
    this.credentialRef = credentialRef.strip();
    if (this.enabled != enabled) {
      this.healthState = enabled ? "UNVERIFIED" : "DISABLED";
      this.lastFailureReason = null;
    }
    this.enabled = enabled;
    this.updatedAt = now;
  }

  public void markHealthy(String observedExitIp, Instant now) {
    this.healthState = enabled ? "HEALTHY" : "DISABLED";
    this.lastVerifiedExitIp = observedExitIp;
    this.lastHealthCheckedAt = now;
    this.lastFailureReason = null;
    this.updatedAt = now;
  }

  public String getBindingProfileId() {
    return bindingProfileId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getProviderId() {
    return providerId;
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

  public boolean isEnabled() {
    return enabled;
  }

  public String getHealthState() {
    return healthState;
  }

  public String getLastVerifiedExitIp() {
    return lastVerifiedExitIp;
  }

  public Instant getLastHealthCheckedAt() {
    return lastHealthCheckedAt;
  }

  public String getLastFailureReason() {
    return lastFailureReason;
  }

  public long getProbeSuccessCount() {
    return probeSuccessCount;
  }

  public long getProbeFailureCount() {
    return probeFailureCount;
  }

  public int getConsecutiveProbeSuccesses() {
    return consecutiveProbeSuccesses;
  }

  public int getConsecutiveProbeFailures() {
    return consecutiveProbeFailures;
  }

  public Double getProbeSuccessEwma() {
    return probeSuccessEwma == null ? null : probeSuccessEwma.doubleValue();
  }

  public Double getProbeLatencyEwmaMs() {
    return probeLatencyEwmaMs == null ? null : probeLatencyEwmaMs.doubleValue();
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
