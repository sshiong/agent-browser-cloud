package io.browsercloud.persistence;

import io.browsercloud.domain.agent.AgentPolicy;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** Session JPA 实体。 */
@Entity
@Table(name = "sessions")
@SQLRestriction("deleted_at IS NULL")
public class SessionEntity {

  @Id
  @Column(name = "id")
  private String id;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "profile_id", nullable = false)
  private String profileId;

  @Column(name = "region", nullable = false)
  private String region;

  @Column(name = "resource_class", nullable = false)
  private String resourceClass;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "policy_hash", nullable = false)
  private String policyHash;

  @Column(name = "group_id")
  private String groupId;

  @Column(name = "human_takeover_enabled", nullable = false)
  private boolean humanTakeoverEnabled;

  @Enumerated(EnumType.STRING)
  @Column(name = "agent_policy", nullable = false)
  private AgentPolicy agentPolicy;

  @Column(name = "extension_ids", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String extensionIds;

  @Column(name = "metadata", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String metadata;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "terminated_at")
  private Instant terminatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "deleted_by")
  private String deletedBy;

  @Column(name = "deletion_batch_id")
  private String deletionBatchId;

  public SessionEntity() {}

  public SessionEntity(
      String id,
      String tenantId,
      String profileId,
      String region,
      String resourceClass,
      String state,
      String policyHash,
      String metadata,
      boolean humanTakeoverEnabled,
      AgentPolicy agentPolicy,
      String extensionIds,
      Instant createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.profileId = profileId;
    this.region = region;
    this.resourceClass = resourceClass;
    this.state = state;
    this.policyHash = policyHash;
    this.metadata = metadata;
    this.humanTakeoverEnabled = humanTakeoverEnabled;
    this.agentPolicy = agentPolicy;
    this.extensionIds = extensionIds;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  // Getters and Setters
  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getProfileId() {
    return profileId;
  }

  public void setProfileId(String profileId) {
    this.profileId = profileId;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public String getResourceClass() {
    return resourceClass;
  }

  public void setResourceClass(String resourceClass) {
    this.resourceClass = resourceClass;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getPolicyHash() {
    return policyHash;
  }

  public String getGroupId() {
    return groupId;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public boolean isHumanTakeoverEnabled() {
    return humanTakeoverEnabled;
  }

  public AgentPolicy getAgentPolicy() {
    return agentPolicy;
  }

  public String getExtensionIds() {
    return extensionIds;
  }

  public void setPolicyHash(String policyHash) {
    this.policyHash = policyHash;
  }

  public String getMetadata() {
    return metadata;
  }

  public void setMetadata(String metadata) {
    this.metadata = metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Instant getTerminatedAt() {
    return terminatedAt;
  }

  public void setTerminatedAt(Instant terminatedAt) {
    this.terminatedAt = terminatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public String getDeletedBy() {
    return deletedBy;
  }

  public void setDeletedBy(String deletedBy) {
    this.deletedBy = deletedBy;
  }

  public String getDeletionBatchId() {
    return deletionBatchId;
  }

  public void setDeletionBatchId(String deletionBatchId) {
    this.deletionBatchId = deletionBatchId;
  }
}
