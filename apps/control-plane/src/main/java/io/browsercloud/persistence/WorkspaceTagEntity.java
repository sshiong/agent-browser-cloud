package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "workspace_tags")
public class WorkspaceTagEntity {

  @Id
  @Column(name = "tag_id")
  private String tagId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String name;

  private String description;

  @Column(nullable = false)
  private String color;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected WorkspaceTagEntity() {}

  public WorkspaceTagEntity(
      String tagId,
      String tenantId,
      String name,
      String description,
      String color,
      String createdBy,
      Instant now) {
    this.tagId = tagId;
    this.tenantId = tenantId;
    this.createdBy = createdBy;
    this.createdAt = now;
    update(name, description, color, now);
  }

  public void update(String name, String description, String color, Instant now) {
    this.name = name.strip();
    this.description = description == null || description.isBlank() ? null : description.strip();
    this.color = color.toUpperCase(Locale.ROOT);
    this.updatedAt = now;
  }

  public String getTagId() {
    return tagId;
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
