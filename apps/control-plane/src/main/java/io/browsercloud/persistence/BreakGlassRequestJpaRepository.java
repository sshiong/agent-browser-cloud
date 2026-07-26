package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BreakGlassRequestJpaRepository
    extends JpaRepository<BreakGlassRequestEntity, String> {

  List<BreakGlassRequestEntity> findAllByTenantIdOrderByRequestedAtDesc(String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select request from BreakGlassRequestEntity request "
          + "where request.requestId = :requestId and request.tenantId = :tenantId")
  Optional<BreakGlassRequestEntity> findForUpdate(
      @Param("requestId") String requestId, @Param("tenantId") String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select request from BreakGlassRequestEntity request "
          + "where request.state = 'ACTIVE' and request.expiresAt <= :now")
  List<BreakGlassRequestEntity> findExpiredActiveForUpdate(@Param("now") Instant now);
}
