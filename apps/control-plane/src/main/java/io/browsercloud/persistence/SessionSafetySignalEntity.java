package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "session_safety_signals",
    uniqueConstraints =
        @UniqueConstraint(
            name = "session_safety_signals_session_id_signal_type_source_key",
            columnNames = {"session_id", "signal_type", "source"}))
public class SessionSafetySignalEntity {

  @Id
  @Column(name = "signal_id")
  private String signalId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "node_id")
  private String nodeId;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "signal_type", nullable = false)
  private String signalType;

  @Column(nullable = false)
  private String source;

  @Column(nullable = false)
  private boolean active;

  @Column(nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String details;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected SessionSafetySignalEntity() {}

  public SessionSafetySignalEntity(
      String signalId,
      String sessionId,
      String tenantId,
      String nodeId,
      long contextEpoch,
      String signalType,
      String source,
      boolean active,
      String details,
      Instant observedAt,
      Instant expiresAt,
      Instant updatedAt) {
    this.signalId = signalId;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.nodeId = nodeId;
    this.contextEpoch = contextEpoch;
    this.signalType = signalType;
    this.source = source;
    this.active = active;
    this.details = details;
    this.observedAt = observedAt;
    this.expiresAt = expiresAt;
    this.updatedAt = updatedAt;
  }

  public void observe(
      String tenantId,
      String nodeId,
      long contextEpoch,
      boolean active,
      String details,
      Instant observedAt,
      Instant expiresAt,
      Instant updatedAt) {
    this.tenantId = tenantId;
    this.nodeId = nodeId;
    this.contextEpoch = contextEpoch;
    this.active = active;
    this.details = details;
    this.observedAt = observedAt;
    this.expiresAt = expiresAt;
    this.updatedAt = updatedAt;
  }

  public String getSignalType() {
    return signalType;
  }

  public String getSource() {
    return source;
  }

  public String getNodeId() {
    return nodeId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public boolean isActive() {
    return active;
  }

  public String getDetails() {
    return details;
  }

  public Instant getObservedAt() {
    return observedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
