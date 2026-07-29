package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionApplicationBindingJpaRepository
    extends JpaRepository<SessionApplicationBindingEntity, String> {

  Optional<SessionApplicationBindingEntity> findBySessionIdAndTenantId(
      String sessionId, String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select binding from SessionApplicationBindingEntity binding "
          + "where binding.sessionId = :sessionId and binding.tenantId = :tenantId")
  Optional<SessionApplicationBindingEntity> findForUpdate(
      @Param("sessionId") String sessionId, @Param("tenantId") String tenantId);
}
