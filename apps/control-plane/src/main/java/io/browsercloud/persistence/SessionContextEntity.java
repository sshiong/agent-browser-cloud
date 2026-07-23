package io.browsercloud.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** Session Context JPA 实体。 */
@Entity
@Table(name = "session_contexts")
@IdClass(SessionContextId.class)
public class SessionContextEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Id
  @Column(name = "context_epoch")
  private long contextEpoch;

  @Column(name = "coordinator_term", nullable = false)
  private long coordinatorTerm;

  @Column(name = "node_id")
  private String nodeId;

  @Column(name = "runtime_build_id")
  private String runtimeBuildId;

  @Column(name = "isolation_profile_id")
  private String isolationProfileId;

  @Column(name = "proxy_binding_id")
  private String proxyBindingId;

  @Column(name = "network_revision", nullable = false)
  private long networkRevision;

  @Column(name = "browser_generation", nullable = false)
  private long browserGeneration;

  @Column(name = "resource_class", nullable = false)
  private String resourceClass;

  @Column(name = "policy_hash", nullable = false)
  private String policyHash;

  @Column(name = "committed_at", nullable = false)
  private Instant committedAt;

  public SessionContextEntity() {}

  // Getters and Setters
  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public long getContextEpoch() {
    return contextEpoch;
  }

  public void setContextEpoch(long contextEpoch) {
    this.contextEpoch = contextEpoch;
  }

  public long getCoordinatorTerm() {
    return coordinatorTerm;
  }

  public void setCoordinatorTerm(long coordinatorTerm) {
    this.coordinatorTerm = coordinatorTerm;
  }

  public String getNodeId() {
    return nodeId;
  }

  public void setNodeId(String nodeId) {
    this.nodeId = nodeId;
  }

  public String getRuntimeBuildId() {
    return runtimeBuildId;
  }

  public void setRuntimeBuildId(String runtimeBuildId) {
    this.runtimeBuildId = runtimeBuildId;
  }

  public String getIsolationProfileId() {
    return isolationProfileId;
  }

  public void setIsolationProfileId(String isolationProfileId) {
    this.isolationProfileId = isolationProfileId;
  }

  public String getProxyBindingId() {
    return proxyBindingId;
  }

  public void setProxyBindingId(String proxyBindingId) {
    this.proxyBindingId = proxyBindingId;
  }

  public long getNetworkRevision() {
    return networkRevision;
  }

  public void setNetworkRevision(long networkRevision) {
    this.networkRevision = networkRevision;
  }

  public long getBrowserGeneration() {
    return browserGeneration;
  }

  public void setBrowserGeneration(long browserGeneration) {
    this.browserGeneration = browserGeneration;
  }

  public String getResourceClass() {
    return resourceClass;
  }

  public void setResourceClass(String resourceClass) {
    this.resourceClass = resourceClass;
  }

  public String getPolicyHash() {
    return policyHash;
  }

  public void setPolicyHash(String policyHash) {
    this.policyHash = policyHash;
  }

  public Instant getCommittedAt() {
    return committedAt;
  }

  public void setCommittedAt(Instant committedAt) {
    this.committedAt = committedAt;
  }
}
