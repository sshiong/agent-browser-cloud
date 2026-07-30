package io.browsercloud.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionProxyBindingAssignmentJpaRepository
    extends JpaRepository<SessionProxyBindingAssignmentEntity, String> {

  Optional<SessionProxyBindingAssignmentEntity> findBySessionIdAndTenantId(
      String sessionId, String tenantId);

  boolean existsByTenantIdAndBindingProfileId(String tenantId, String bindingProfileId);
}
