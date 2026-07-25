package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tool_capability_uses")
public class ToolCapabilityUseEntity {

  @Id
  @Column(name = "token_id")
  private String tokenId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "tool_id", nullable = false)
  private String toolId;

  @Column(name = "used_at", nullable = false)
  private Instant usedAt;

  protected ToolCapabilityUseEntity() {}
}
