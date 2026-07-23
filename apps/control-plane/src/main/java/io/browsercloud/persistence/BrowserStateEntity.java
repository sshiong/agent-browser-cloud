package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 每个 Session 最新一份可重建 Browser State。 */
@Entity
@Table(name = "browser_states")
public class BrowserStateEntity {

  @Id
  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "context_epoch", nullable = false)
  private long contextEpoch;

  @Column(name = "state_version", nullable = false)
  private long stateVersion;

  @Column(name = "state_json", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String stateJson;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public BrowserStateEntity() {}

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public void setContextEpoch(long contextEpoch) {
    this.contextEpoch = contextEpoch;
  }

  public long getStateVersion() {
    return stateVersion;
  }

  public void setStateVersion(long stateVersion) {
    this.stateVersion = stateVersion;
  }

  public String getStateJson() {
    return stateJson;
  }

  public void setStateJson(String stateJson) {
    this.stateJson = stateJson;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
