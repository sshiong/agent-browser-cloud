package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "session_tag_assignments")
public class SessionTagAssignmentEntity {

  @Id
  @Column(name = "assignment_id")
  private String assignmentId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tag_id", nullable = false)
  private String tagId;

  @Column(name = "assigned_by", nullable = false)
  private String assignedBy;

  @Column(name = "assigned_at", nullable = false)
  private Instant assignedAt;

  protected SessionTagAssignmentEntity() {}

  public SessionTagAssignmentEntity(
      String assignmentId,
      String tenantId,
      String sessionId,
      String tagId,
      String assignedBy,
      Instant assignedAt) {
    this.assignmentId = assignmentId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.tagId = tagId;
    this.assignedBy = assignedBy;
    this.assignedAt = assignedAt;
  }

  public String getAssignmentId() {
    return assignmentId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTagId() {
    return tagId;
  }

  public String getAssignedBy() {
    return assignedBy;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }
}
