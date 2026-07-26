package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SecureDebugSessionJpaRepository
    extends JpaRepository<SecureDebugSessionEntity, String> {

  List<SecureDebugSessionEntity> findAllByTenantIdOrderByStartedAtDesc(String tenantId);

  Optional<SecureDebugSessionEntity> findByBreakGlassRequestIdAndTenantId(
      String breakGlassRequestId, String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select session from SecureDebugSessionEntity session "
          + "where session.debugSessionId = :debugSessionId and session.tenantId = :tenantId")
  Optional<SecureDebugSessionEntity> findForUpdate(
      @Param("debugSessionId") String debugSessionId, @Param("tenantId") String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select session from SecureDebugSessionEntity session "
          + "where session.state = 'ACTIVE' and session.expiresAt <= :now")
  List<SecureDebugSessionEntity> findExpiredForUpdate(@Param("now") Instant now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from SecureDebugSessionEntity session where session.state = 'ACTIVE'")
  List<SecureDebugSessionEntity> findAllActiveForUpdate();
}
