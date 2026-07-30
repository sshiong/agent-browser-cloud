package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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

  public String getRegion() {
    return region;
  }

  public String getExpectedExitIp() {
    return expectedExitIp;
  }

  public String getCredentialRef() {
    return credentialRef;
  }
}
