package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionResourcePolicyJpaRepository
    extends JpaRepository<SessionResourcePolicyEntity, String> {
  Optional<SessionResourcePolicyEntity> findBySessionIdAndTenantId(
      String sessionId, String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select policy from SessionResourcePolicyEntity policy"
          + " where policy.sessionId = :sessionId and policy.tenantId = :tenantId")
  Optional<SessionResourcePolicyEntity> findForUpdate(
      @Param("sessionId") String sessionId, @Param("tenantId") String tenantId);

  @Query(
      value =
          """
          SELECT policy.*
          FROM session_resource_policies policy
          JOIN sessions session_record ON session_record.id = policy.session_id
          WHERE session_record.state IN ('RUNNING', 'DEGRADED')
            AND session_record.deleted_at IS NULL
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
            AND session_record.deleted_at IS NULL
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
