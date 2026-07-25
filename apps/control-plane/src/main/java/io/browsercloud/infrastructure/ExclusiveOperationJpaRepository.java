package io.browsercloud.infrastructure;

import io.browsercloud.persistence.ExclusiveOperationEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Exclusive Operation JPA Repository。 */
@Repository
public interface ExclusiveOperationJpaRepository
    extends JpaRepository<ExclusiveOperationEntity, String> {

  Optional<ExclusiveOperationEntity> findBySessionIdAndState(String sessionId, String state);

  @Query(
      """
      select coalesce(max(operation.operationEpoch), 0) + 1
        from ExclusiveOperationEntity operation
       where operation.sessionId = :sessionId
      """)
  long nextOperationEpoch(String sessionId);

  long countBySessionIdAndModeAndCreatedAtAfter(String sessionId, String mode, Instant createdAt);
}
