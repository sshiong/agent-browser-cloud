package io.browsercloud.infrastructure;

import io.browsercloud.persistence.ExclusiveOperationEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Exclusive Operation JPA Repository。 */
@Repository
public interface ExclusiveOperationJpaRepository
    extends JpaRepository<ExclusiveOperationEntity, String> {

  Optional<ExclusiveOperationEntity> findBySessionIdAndState(String sessionId, String state);

  List<ExclusiveOperationEntity> findAllBySessionIdInAndState(
      Collection<String> sessionIds, String state);

  @Query(
      """
      select coalesce(max(operation.operationEpoch), 0) + 1
        from ExclusiveOperationEntity operation
       where operation.sessionId = :sessionId
      """)
  long nextOperationEpoch(String sessionId);

  long countBySessionIdAndModeAndCreatedAtAfter(String sessionId, String mode, Instant createdAt);

  @Query(
      """
      select operation
        from ExclusiveOperationEntity operation
       where operation.state = 'ACTIVE'
         and operation.workflowId is null
         and operation.deadline <= :now
       order by operation.deadline
      """)
  List<ExclusiveOperationEntity> findExpiredWithoutWorkflow(Instant now, Pageable pageable);
}
