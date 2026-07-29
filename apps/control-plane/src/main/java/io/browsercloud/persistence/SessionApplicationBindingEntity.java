package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "session_application_bindings")
public class SessionApplicationBindingEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "application_id", nullable = false)
  private String applicationId;

  @Column(name = "contract_id", nullable = false)
  private String contractId;

  @Column(name = "contract_version", nullable = false)
  private long contractVersion;

  @Column(name = "bound_at", nullable = false)
  private Instant boundAt;

  protected SessionApplicationBindingEntity() {}

  public SessionApplicationBindingEntity(
      String sessionId, String tenantId, String applicationId, String contractId, Instant boundAt) {
    this(sessionId, tenantId, applicationId, contractId, 1, boundAt);
  }

  public SessionApplicationBindingEntity(
      String sessionId,
      String tenantId,
      String applicationId,
      String contractId,
      long contractVersion,
      Instant boundAt) {
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.applicationId = applicationId;
    this.contractId = contractId;
    this.contractVersion = contractVersion;
    this.boundAt = boundAt;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getApplicationId() {
    return applicationId;
  }

  public String getContractId() {
    return contractId;
  }

  public long getContractVersion() {
    return contractVersion;
  }

  public Instant getBoundAt() {
    return boundAt;
  }
}
