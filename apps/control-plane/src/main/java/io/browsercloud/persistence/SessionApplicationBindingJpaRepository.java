package io.browsercloud.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionApplicationBindingJpaRepository
    extends JpaRepository<SessionApplicationBindingEntity, String> {

  Optional<SessionApplicationBindingEntity> findBySessionIdAndTenantId(
      String sessionId, String tenantId);
}
