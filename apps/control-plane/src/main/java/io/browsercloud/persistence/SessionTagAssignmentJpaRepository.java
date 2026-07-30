package io.browsercloud.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionTagAssignmentJpaRepository
    extends JpaRepository<SessionTagAssignmentEntity, String> {

  List<SessionTagAssignmentEntity> findAllByTenantIdAndTagIdOrderByAssignedAtDesc(
      String tenantId, String tagId);

  List<SessionTagAssignmentEntity> findAllByTenantIdAndSessionIdOrderByAssignedAtAsc(
      String tenantId, String sessionId);

  List<SessionTagAssignmentEntity> findAllByTenantIdAndSessionIdInOrderByAssignedAtAsc(
      String tenantId, Collection<String> sessionIds);

  List<SessionTagAssignmentEntity> findAllByTenantIdOrderByAssignedAtDesc(String tenantId);

  Optional<SessionTagAssignmentEntity> findByTenantIdAndTagIdAndSessionId(
      String tenantId, String tagId, String sessionId);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO session_tag_assignments (
            assignment_id, tenant_id, session_id, tag_id, assigned_by, assigned_at
          ) VALUES (
            :assignmentId, :tenantId, :sessionId, :tagId, :assignedBy, :assignedAt
          )
          ON CONFLICT (session_id, tag_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("assignmentId") String assignmentId,
      @Param("tenantId") String tenantId,
      @Param("sessionId") String sessionId,
      @Param("tagId") String tagId,
      @Param("assignedBy") String assignedBy,
      @Param("assignedAt") Instant assignedAt);
}
