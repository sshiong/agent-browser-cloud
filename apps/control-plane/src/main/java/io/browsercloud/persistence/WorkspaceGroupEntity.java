package io.browsercloud.persistence;

import io.browsercloud.domain.resource.MaximumReachedPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "workspace_groups")
public class WorkspaceGroupEntity {

  @Id
  @Column(name = "group_id")
  private String groupId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String name;

  private String description;

  @Column(nullable = false)
  private String color;

  @Column(name = "default_on_maximum_reached", nullable = false)
  private String defaultOnMaximumReached;

  @Column(name = "default_allow_migration", nullable = false)
  private boolean defaultAllowMigration;

  @Column(name = "default_allow_hibernate", nullable = false)
  private boolean defaultAllowHibernate;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected WorkspaceGroupEntity() {}

  public WorkspaceGroupEntity(
      String groupId,
      String tenantId,
      String name,
      String description,
      String color,
      MaximumReachedPolicy defaultOnMaximumReached,
      boolean defaultAllowMigration,
      boolean defaultAllowHibernate,
      String createdBy,
      Instant now) {
    this.groupId = groupId;
    this.tenantId = tenantId;
    update(
        name,
        description,
        color,
        defaultOnMaximumReached,
        defaultAllowMigration,
        defaultAllowHibernate,
        now);
    this.createdBy = createdBy;
    this.createdAt = now;
  }

  public void update(
      String name,
      String description,
      String color,
      MaximumReachedPolicy defaultOnMaximumReached,
      boolean defaultAllowMigration,
      boolean defaultAllowHibernate,
      Instant now) {
    this.name = name.strip();
    this.description = description == null || description.isBlank() ? null : description.strip();
    this.color = color.toUpperCase(java.util.Locale.ROOT);
    this.defaultOnMaximumReached = defaultOnMaximumReached.name();
    this.defaultAllowMigration = defaultAllowMigration;
    this.defaultAllowHibernate = defaultAllowHibernate;
    this.updatedAt = now;
  }

  public String getGroupId() {
    return groupId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getColor() {
    return color;
  }

  public MaximumReachedPolicy defaultOnMaximumReached() {
    return MaximumReachedPolicy.valueOf(defaultOnMaximumReached);
  }

  public boolean isDefaultAllowMigration() {
    return defaultAllowMigration;
  }

  public boolean isDefaultAllowHibernate() {
    return defaultAllowHibernate;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
