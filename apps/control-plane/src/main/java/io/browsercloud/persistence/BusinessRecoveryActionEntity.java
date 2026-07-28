package io.browsercloud.persistence;

import io.browsercloud.api.BusinessRecoveryModels.RecoveryAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "business_recovery_actions")
public class BusinessRecoveryActionEntity {

  @Id
  @Column(name = "action_id")
  private String actionId;

  @Column(name = "migration_id", nullable = false)
  private String migrationId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "contract_id", nullable = false)
  private String contractId;

  @Column(name = "contract_version", nullable = false)
  private long contractVersion;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Column(name = "action_type", nullable = false)
  private String actionType;

  @Column(name = "target_url")
  private String targetUrl;

  @Column(name = "base_state_version", nullable = false)
  private long baseStateVersion;

  @Column(name = "resulting_state_version")
  private Long resultingStateVersion;

  @Column(nullable = false)
  private String state;

  @Column(name = "command_message_id", nullable = false)
  private String commandMessageId;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "deadline_at", nullable = false)
  private Instant deadlineAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "dispatched_at")
  private Instant dispatchedAt;

  @Column(name = "acknowledged_at")
  private Instant acknowledgedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected BusinessRecoveryActionEntity() {}

  public BusinessRecoveryActionEntity(
      String actionId,
      String migrationId,
      String sessionId,
      String tenantId,
      String contractId,
      long contractVersion,
      int attemptNumber,
      RecoveryAction action,
      String targetUrl,
      long baseStateVersion,
      String commandMessageId,
      Instant deadlineAt,
      Instant now) {
    this.actionId = actionId;
    this.migrationId = migrationId;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.contractId = contractId;
    this.contractVersion = contractVersion;
    this.attemptNumber = attemptNumber;
    this.actionType = action.name();
    this.targetUrl = targetUrl;
    this.baseStateVersion = baseStateVersion;
    this.commandMessageId = commandMessageId;
    this.deadlineAt = deadlineAt;
    this.state = "REQUESTED";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void executing(Instant now) {
    if (!"REQUESTED".equals(state)) {
      return;
    }
    state = "EXECUTING";
    dispatchedAt = now;
    updatedAt = now;
  }

  public void committed(long stateVersion, Instant now) {
    if ("COMMITTED".equals(state)) {
      return;
    }
    state = "ACKNOWLEDGED";
    acknowledgedAt = now;
    resultingStateVersion = stateVersion;
    state = "COMMITTED";
    completedAt = now;
    updatedAt = now;
  }

  public void failed(String code, Instant now) {
    if ("COMMITTED".equals(state) || "FAILED".equals(state)) {
      return;
    }
    state = "FAILED";
    errorCode = code;
    completedAt = now;
    updatedAt = now;
  }

  public String getActionId() {
    return actionId;
  }

  public String getMigrationId() {
    return migrationId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getContractId() {
    return contractId;
  }

  public long getContractVersion() {
    return contractVersion;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public RecoveryAction action() {
    return RecoveryAction.valueOf(actionType);
  }

  public String getTargetUrl() {
    return targetUrl;
  }

  public long getBaseStateVersion() {
    return baseStateVersion;
  }

  public Long getResultingStateVersion() {
    return resultingStateVersion;
  }

  public String getState() {
    return state;
  }

  public String getCommandMessageId() {
    return commandMessageId;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public Instant getDeadlineAt() {
    return deadlineAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
