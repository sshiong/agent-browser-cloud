package io.browsercloud.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionResourcePolicyJpaRepository
    extends JpaRepository<SessionResourcePolicyEntity, String> {
  Optional<SessionResourcePolicyEntity> findBySessionIdAndTenantId(
      String sessionId, String tenantId);

  @Query(
      value =
          """
          SELECT policy.*
          FROM session_resource_policies policy
          JOIN sessions session_record ON session_record.id = policy.session_id
          WHERE session_record.state IN ('RUNNING', 'DEGRADED')
            AND (policy.last_evaluated_at IS NULL OR policy.last_evaluated_at <= :dueBefore)
          ORDER BY policy.last_evaluated_at NULLS FIRST
          """,
      nativeQuery = true)
  List<SessionResourcePolicyEntity> findDueActive(
      @Param("dueBefore") Instant dueBefore, Pageable pageable);

  @Query(
      value =
          """
          SELECT policy.*
          FROM session_resource_policies policy
          JOIN sessions session_record ON session_record.id = policy.session_id
          WHERE session_record.state IN ('RUNNING', 'DEGRADED')
            AND (
              policy.last_cost_evaluated_at IS NULL
              OR policy.last_cost_evaluated_at <= :dueBefore
            )
          ORDER BY policy.last_cost_evaluated_at NULLS FIRST
          """,
      nativeQuery = true)
  List<SessionResourcePolicyEntity> findDueCostEvaluation(
      @Param("dueBefore") Instant dueBefore, Pageable pageable);
}
