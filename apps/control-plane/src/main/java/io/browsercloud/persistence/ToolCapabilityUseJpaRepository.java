package io.browsercloud.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ToolCapabilityUseJpaRepository
    extends JpaRepository<ToolCapabilityUseEntity, String> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO tool_capability_uses
              (token_id, tenant_id, session_id, task_id, tool_id, used_at)
          VALUES (:tokenId, :tenantId, :sessionId, :taskId, :toolId, :usedAt)
          ON CONFLICT (token_id) DO NOTHING
          """,
      nativeQuery = true)
  int claim(
      @Param("tokenId") String tokenId,
      @Param("tenantId") String tenantId,
      @Param("sessionId") String sessionId,
      @Param("taskId") String taskId,
      @Param("toolId") String toolId,
      @Param("usedAt") Instant usedAt);
}
