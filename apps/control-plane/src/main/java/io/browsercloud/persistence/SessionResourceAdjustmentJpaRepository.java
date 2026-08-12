package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SessionResourceAdjustmentJpaRepository
    extends JpaRepository<SessionResourceAdjustmentEntity, String> {

  Optional<SessionResourceAdjustmentEntity> findFirstBySessionIdOrderByRequestedAtDesc(
      String sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select adjustment from SessionResourceAdjustmentEntity adjustment where adjustment.operationId = :operationId")
  Optional<SessionResourceAdjustmentEntity> findForUpdate(String operationId);
}
