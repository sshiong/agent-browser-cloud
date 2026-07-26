package io.browsercloud.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DurableWorkflowJpaRepository extends JpaRepository<DurableWorkflowEntity, String> {

  Optional<DurableWorkflowEntity> findByTenantIdAndIdempotencyKey(
      String tenantId, String idempotencyKey);

  Optional<DurableWorkflowEntity>
      findFirstByTenantIdAndSessionIdAndOperationEpochOrderByAttemptDesc(
          String tenantId, String sessionId, long operationEpoch);

  @Query(
      """
      select workflow from DurableWorkflowEntity workflow
       where workflow.state in ('DISPATCHED', 'RUNNING', 'COMPLETING')
         and workflow.phaseDeadline <= :now
       order by workflow.priority desc, workflow.phaseDeadline asc
      """)
  List<DurableWorkflowEntity> findExpired(Instant now, Pageable pageable);
}
