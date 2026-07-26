package io.browsercloud.persistence;

import io.browsercloud.domain.session.ResourceClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "session_resource_demands")
public class SessionResourceDemandEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "requested_resource_class", nullable = false)
  private String requestedResourceClass;

  @Column(name = "requested_tabs", nullable = false)
  private int requestedTabs;

  @Column(name = "agent_actions_per_minute", nullable = false)
  private int agentActionsPerMinute;

  @Column(name = "remote_desktop", nullable = false)
  private boolean remoteDesktop;

  @Column(name = "web3_workload", nullable = false)
  private boolean web3Workload;

  @Column(name = "media_workload", nullable = false)
  private boolean mediaWorkload;

  @Column(name = "requested_media_streams", nullable = false)
  private int requestedMediaStreams;

  @Column(name = "media_bitrate_kbps", nullable = false)
  private int mediaBitrateKbps;

  @Column(name = "extension_ids", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String extensionIds;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SessionResourceDemandEntity() {}

  public SessionResourceDemandEntity(
      String sessionId,
      String tenantId,
      ResourceClass requestedResourceClass,
      int requestedTabs,
      int agentActionsPerMinute,
      boolean remoteDesktop,
      boolean web3Workload,
      boolean mediaWorkload,
      int requestedMediaStreams,
      int mediaBitrateKbps,
      String extensionIds,
      Instant now) {
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.requestedResourceClass = requestedResourceClass.name();
    this.requestedTabs = requestedTabs;
    this.agentActionsPerMinute = agentActionsPerMinute;
    this.remoteDesktop = remoteDesktop;
    this.web3Workload = web3Workload;
    this.mediaWorkload = mediaWorkload;
    this.requestedMediaStreams = requestedMediaStreams;
    this.mediaBitrateKbps = mediaBitrateKbps;
    this.extensionIds = extensionIds;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public ResourceClass resourceClass() {
    return ResourceClass.valueOf(requestedResourceClass);
  }

  public int getRequestedTabs() {
    return requestedTabs;
  }

  public int getAgentActionsPerMinute() {
    return agentActionsPerMinute;
  }

  public boolean isRemoteDesktop() {
    return remoteDesktop;
  }

  public boolean isWeb3Workload() {
    return web3Workload;
  }

  public boolean isMediaWorkload() {
    return mediaWorkload;
  }

  public int getRequestedMediaStreams() {
    return requestedMediaStreams;
  }

  public int getMediaBitrateKbps() {
    return mediaBitrateKbps;
  }

  public String getExtensionIds() {
    return extensionIds;
  }
}
