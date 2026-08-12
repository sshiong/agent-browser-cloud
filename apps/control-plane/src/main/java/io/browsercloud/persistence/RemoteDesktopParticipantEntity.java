package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "remote_desktop_participants")
public class RemoteDesktopParticipantEntity {
  @Id
  @Column(name = "connection_id")
  private String connectionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "actor_id")
  private String actorId;

  @Column(name = "access_mode")
  private String accessMode;

  @Column(name = "view_only")
  private Boolean viewOnly;

  @Column(nullable = false)
  private String state;

  @Column(nullable = false)
  private String reason;

  @Column(name = "connected_at")
  private Instant connectedAt;

  @Column(name = "disconnected_at")
  private Instant disconnectedAt;

  @Column(name = "revoked_by")
  private String revokedBy;

  @Column(name = "revoke_requested_at")
  private Instant revokeRequestedAt;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected RemoteDesktopParticipantEntity() {}

  public RemoteDesktopParticipantEntity(
      String connectionId, String tenantId, String sessionId, long contextEpoch, Instant now) {
    this.connectionId = connectionId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.contextEpoch = contextEpoch;
    this.state = "REVOKE_REQUESTED";
    this.reason = "ADMIN_REQUESTED";
    this.observedAt = now;
    this.updatedAt = now;
    this.revokeRequestedAt = now;
  }

  public void apply(
      long eventContextEpoch,
      String eventActorId,
      String eventAccessMode,
      boolean eventViewOnly,
      String eventState,
      String eventReason,
      String eventRevokedBy,
      Instant eventObservedAt) {
    if (eventContextEpoch < contextEpoch || eventObservedAt.isBefore(observedAt)) return;
    contextEpoch = eventContextEpoch;
    if (eventActorId != null && !eventActorId.isBlank()) actorId = eventActorId;
    if (eventAccessMode != null && !eventAccessMode.isBlank()) accessMode = eventAccessMode;
    if (eventActorId != null && !eventActorId.isBlank()) viewOnly = eventViewOnly;
    var preserveRevoked =
        "REVOKED".equals(state)
            && "DISCONNECTED".equals(eventState)
            && "ADMIN_REVOKED".equals(eventReason);
    if (!preserveRevoked) {
      state = eventState;
      reason = eventReason;
    }
    observedAt = eventObservedAt;
    updatedAt = eventObservedAt;
    if ("CONNECTED".equals(eventState)) {
      connectedAt = eventObservedAt;
      disconnectedAt = null;
    } else if ("DISCONNECTED".equals(eventState) || "REVOKED".equals(eventState)) {
      disconnectedAt = eventObservedAt;
    }
    if (eventRevokedBy != null && !eventRevokedBy.isBlank()) revokedBy = eventRevokedBy;
  }

  public void requestRevoke(String actorId, Instant now) {
    if ("REVOKED".equals(state) || "DISCONNECTED".equals(state)) return;
    state = "REVOKE_REQUESTED";
    reason = "ADMIN_REQUESTED";
    revokedBy = actorId;
    revokeRequestedAt = now;
    updatedAt = now;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public String getActorId() {
    return actorId;
  }

  public String getAccessMode() {
    return accessMode;
  }

  public Boolean getViewOnly() {
    return viewOnly;
  }

  public String getState() {
    return state;
  }

  public String getReason() {
    return reason;
  }

  public Instant getConnectedAt() {
    return connectedAt;
  }

  public Instant getDisconnectedAt() {
    return disconnectedAt;
  }

  public String getRevokedBy() {
    return revokedBy;
  }

  public Instant getRevokeRequestedAt() {
    return revokeRequestedAt;
  }

  public Instant getObservedAt() {
    return observedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
