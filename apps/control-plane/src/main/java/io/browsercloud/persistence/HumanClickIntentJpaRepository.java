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

public interface HumanClickIntentJpaRepository
    extends JpaRepository<HumanClickIntentEntity, String> {

  Optional<HumanClickIntentEntity> findByTenantIdAndIdempotencyKey(
      String tenantId, String idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select intent from HumanClickIntentEntity intent where intent.operationId = :operationId")
  Optional<HumanClickIntentEntity> findByOperationIdForUpdate(
      @Param("operationId") String operationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select intent from HumanClickIntentEntity intent
      where intent.state in ('AUTHORIZED', 'EXECUTING') and intent.expiresAt <= :now
      order by intent.expiresAt
      """)
  List<HumanClickIntentEntity> findExpiredForUpdate(@Param("now") Instant now, Pageable pageable);
}
