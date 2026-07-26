package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_dead_letters")
public class WorkflowDeadLetterEntity {

  @Id
  @Column(name = "dead_letter_id")
  private String deadLetterId;

  @Column(name = "workflow_id", nullable = false)
  private String workflowId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "operation_id", nullable = false)
  private String operationId;

  @Column(nullable = false)
  private String reason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String evidence;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public WorkflowDeadLetterEntity() {}

  public WorkflowDeadLetterEntity(
      String deadLetterId,
      String workflowId,
      String tenantId,
      String sessionId,
      String operationId,
      String reason,
      String evidence,
      Instant createdAt) {
    this.deadLetterId = deadLetterId;
    this.workflowId = workflowId;
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.operationId = operationId;
    this.reason = reason;
    this.evidence = evidence;
    this.createdAt = createdAt;
  }
}
