package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Single-use user authorization for exactly one state-bound Human Assist click. */
@Entity
@Table(name = "human_click_intents")
public class HumanClickIntentEntity {

  @Id
  @Column(name = "intent_id")
  private String intentId;

  @Column(name = "challenge_event_id", nullable = false)
  private String challengeEventId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "state_version", nullable = false)
  private long stateVersion;

  @Column(name = "target_revision", nullable = false)
  private long targetRevision;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "allowed_region", nullable = false, columnDefinition = "jsonb")
  private String allowedRegion;

  @Column(name = "allowed_target_ref", nullable = false)
  private String allowedTargetRef;

  @Column(name = "visual_anchor_hash", nullable = false)
  private String visualAnchorHash;

  @Column(name = "allowed_action_count", nullable = false)
  private int allowedActionCount;

  @Column(name = "consumed_count", nullable = false)
  private int consumedCount;

  @Column(name = "authorization_event_id", nullable = false)
  private String authorizationEventId;

  @Column(name = "operation_id")
  private String operationId;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(nullable = false)
  private String state;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected HumanClickIntentEntity() {}

  public HumanClickIntentEntity(
      String intentId,
      String challengeEventId,
      String tenantId,
      String userId,
      String sessionId,
      long contextEpoch,
      long stateVersion,
      long targetRevision,
      String allowedRegion,
      String allowedTargetRef,
      String visualAnchorHash,
      String authorizationEventId,
      String requestId,
      String idempotencyKey,
      Instant expiresAt,
      Instant now) {
    this.intentId = intentId;
    this.challengeEventId = challengeEventId;
    this.tenantId = tenantId;
    this.userId = userId;
    this.sessionId = sessionId;
    this.contextEpoch = contextEpoch;
    this.stateVersion = stateVersion;
    this.targetRevision = targetRevision;
    this.allowedRegion = allowedRegion;
    this.allowedTargetRef = allowedTargetRef;
    this.visualAnchorHash = visualAnchorHash;
    this.allowedActionCount = 1;
    this.consumedCount = 0;
    this.authorizationEventId = authorizationEventId;
    this.requestId = requestId;
    this.idempotencyKey = idempotencyKey;
    this.state = "AUTHORIZED";
    this.expiresAt = expiresAt;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void consume(String operationId, Instant now) {
    if (!"AUTHORIZED".equals(state) || consumedCount != 0) {
      throw new IllegalStateException("human click intent is already consumed");
    }
    this.operationId = operationId;
    this.consumedCount = 1;
    this.consumedAt = now;
    this.state = "EXECUTING";
    this.updatedAt = now;
  }

  public void committed(Instant now) {
    if ("COMMITTED".equals(state)) return;
    requireExecuting();
    state = "COMMITTED";
    completedAt = now;
    updatedAt = now;
  }

  public void failed(String code, Instant now) {
    if ("FAILED".equals(state)) return;
    requireExecuting();
    state = "FAILED";
    errorCode = code;
    completedAt = now;
    updatedAt = now;
  }

  public void expire(Instant now) {
    if (!"AUTHORIZED".equals(state)) return;
    state = "EXPIRED";
    errorCode = "HUMAN_ASSIST_EXPIRED";
    completedAt = now;
    updatedAt = now;
  }

  private void requireExecuting() {
    if (!"EXECUTING".equals(state) || consumedCount != 1) {
      throw new IllegalStateException("human click intent is not executing");
    }
  }

  public String getIntentId() {
    return intentId;
  }

  public String getChallengeEventId() {
    return challengeEventId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getUserId() {
    return userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public long getStateVersion() {
    return stateVersion;
  }

  public long getTargetRevision() {
    return targetRevision;
  }

  public String getAllowedRegion() {
    return allowedRegion;
  }

  public String getAllowedTargetRef() {
    return allowedTargetRef;
  }

  public String getVisualAnchorHash() {
    return visualAnchorHash;
  }

  public int getAllowedActionCount() {
    return allowedActionCount;
  }

  public int getConsumedCount() {
    return consumedCount;
  }

  public String getAuthorizationEventId() {
    return authorizationEventId;
  }

  public String getOperationId() {
    return operationId;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getState() {
    return state;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getErrorCode() {
    return errorCode;
  }
}
