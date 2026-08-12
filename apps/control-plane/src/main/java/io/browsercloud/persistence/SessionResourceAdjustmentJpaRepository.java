package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SessionResourceAdjustmentJpaRepository
    extends JpaRepository<SessionResourceAdjustmentEntity, String> {

  @Query(
      value =
          """
          SELECT adjustment.*
            FROM session_resource_adjustments adjustment
            JOIN exclusive_operations operation
              ON operation.operation_id = adjustment.operation_id
           WHERE adjustment.session_id = :sessionId
           ORDER BY adjustment.requested_at DESC, operation.operation_epoch DESC
           LIMIT 1
          """,
      nativeQuery = true)
  Optional<SessionResourceAdjustmentEntity> findLatestBySessionId(String sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select adjustment from SessionResourceAdjustmentEntity adjustment where adjustment.operationId = :operationId")
  Optional<SessionResourceAdjustmentEntity> findForUpdate(String operationId);
}
