package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "session_application_rebind_operations")
public class SessionApplicationRebindEntity {
  @Id
  @Column(name = "operation_id")
  private String operationId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "application_id", nullable = false)
  private String applicationId;

  @Column(name = "contract_id", nullable = false)
  private String contractId;

  @Column(name = "previous_contract_version", nullable = false)
  private long previousContractVersion;

  @Column(name = "target_contract_version", nullable = false)
  private long targetContractVersion;

  @Column(name = "actor_id", nullable = false)
  private String actorId;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "completed_at", nullable = false)
  private Instant completedAt;

  protected SessionApplicationRebindEntity() {}

  public SessionApplicationRebindEntity(
      String operationId,
      String tenantId,
      String sessionId,
      String applicationId,
      String contractId,
      long previousContractVersion,
      long targetContractVersion,
      String actorId,
      String requestId,
      Instant now) {
    this.operationId = operationId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.applicationId = applicationId;
    this.contractId = contractId;
    this.previousContractVersion = previousContractVersion;
    this.targetContractVersion = targetContractVersion;
    this.actorId = actorId;
    this.requestId = requestId;
    this.createdAt = now;
    this.completedAt = now;
  }

  public String getOperationId() {
    return operationId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getApplicationId() {
    return applicationId;
  }

  public String getContractId() {
    return contractId;
  }

  public long getPreviousContractVersion() {
    return previousContractVersion;
  }

  public long getTargetContractVersion() {
    return targetContractVersion;
  }

  public String getActorId() {
    return actorId;
  }

  public String getRequestId() {
    return requestId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
