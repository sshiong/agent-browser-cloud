package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "proxy_allocations")
public class ProxyAllocationEntity {

  @Id
  @Column(name = "allocation_id")
  private String allocationId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "provider", nullable = false)
  private String provider;

  @Column(name = "endpoint", nullable = false)
  private String endpoint;

  @Column(name = "protocol", nullable = false)
  private String protocol;

  @Column(name = "country")
  private String country;

  @Column(name = "asn")
  private String asn;

  @Column(name = "ip_type")
  private String ipType;

  @Column(name = "credential_ref")
  private String credentialRef;

  @Column(name = "exit_ip")
  private String exitIp;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "allocated_at", nullable = false)
  private Instant allocatedAt;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ProxyAllocationEntity() {}

  public ProxyAllocationEntity(
      String allocationId,
      String tenantId,
      String sessionId,
      String provider,
      String endpoint,
      Instant now) {
    this.allocationId = allocationId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.provider = provider;
    this.endpoint = endpoint;
    this.protocol = "HTTP";
    this.ipType = "STATIC";
    this.credentialRef = "";
    this.state = "ALLOCATED";
    this.allocatedAt = now;
    this.updatedAt = now;
  }

  public String getAllocationId() {
    return allocationId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getProvider() {
    return provider;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public String getProtocol() {
    return protocol;
  }

  public String getCountry() {
    return country;
  }

  public String getAsn() {
    return asn;
  }

  public String getExitIp() {
    return exitIp;
  }

  public String getState() {
    return state;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public Instant getReleasedAt() {
    return releasedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void bind(String observedExitIp, String observedCountry, String observedAsn, Instant now) {
    this.exitIp = observedExitIp;
    this.country = observedCountry;
    this.asn = observedAsn;
    this.state = "BOUND";
    this.verifiedAt = now;
    this.updatedAt = now;
    this.failureReason = null;
  }

  public void release(Instant now) {
    if ("RELEASED".equals(state)) {
      return;
    }
    this.state = "RELEASED";
    this.releasedAt = now;
    this.updatedAt = now;
  }
}
