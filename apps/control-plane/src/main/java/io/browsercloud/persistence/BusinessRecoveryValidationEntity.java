package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "business_recovery_validations")
public class BusinessRecoveryValidationEntity {

  @Id
  @Column(name = "validation_id")
  private String validationId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "application_id")
  private String applicationId;

  @Column(name = "contract_id")
  private String contractId;

  @Column(name = "contract_version")
  private Long contractVersion;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "state_version", nullable = false)
  private long stateVersion;

  @Column(name = "verdict", nullable = false)
  private String verdict;

  @Column(name = "ready", nullable = false)
  private boolean ready;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
  private String evidence;

  @Column(name = "source", nullable = false)
  private String source;

  @Column(name = "actor_id", nullable = false)
  private String actorId;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Column(name = "evaluated_at", nullable = false)
  private Instant evaluatedAt;

  protected BusinessRecoveryValidationEntity() {}

  public BusinessRecoveryValidationEntity(
      String validationId,
      String tenantId,
      String sessionId,
      String applicationId,
      String contractId,
      Long contractVersion,
      long contextEpoch,
      long stateVersion,
      String verdict,
      boolean ready,
      String evidence,
      String source,
      String actorId,
      String requestId,
      Instant evaluatedAt) {
    this.validationId = validationId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.applicationId = applicationId;
    this.contractId = contractId;
    this.contractVersion = contractVersion;
    this.contextEpoch = contextEpoch;
    this.stateVersion = stateVersion;
    this.verdict = verdict;
    this.ready = ready;
    this.evidence = evidence;
    this.source = source;
    this.actorId = actorId;
    this.requestId = requestId;
    this.evaluatedAt = evaluatedAt;
  }

  public String getValidationId() {
    return validationId;
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

  public Long getContractVersion() {
    return contractVersion;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public long getStateVersion() {
    return stateVersion;
  }

  public String getVerdict() {
    return verdict;
  }

  public boolean isReady() {
    return ready;
  }

  public String getEvidence() {
    return evidence;
  }

  public String getSource() {
    return source;
  }

  public String getRequestId() {
    return requestId;
  }

  public Instant getEvaluatedAt() {
    return evaluatedAt;
  }
}
