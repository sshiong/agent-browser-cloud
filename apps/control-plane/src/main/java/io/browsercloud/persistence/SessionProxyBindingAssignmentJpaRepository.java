package io.browsercloud.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionProxyBindingAssignmentJpaRepository
    extends JpaRepository<SessionProxyBindingAssignmentEntity, String> {

  Optional<SessionProxyBindingAssignmentEntity> findBySessionIdAndTenantId(
      String sessionId, String tenantId);

  List<SessionProxyBindingAssignmentEntity> findAllByTenantIdAndSessionIdIn(
      String tenantId, Collection<String> sessionIds);

  boolean existsByTenantIdAndBindingProfileId(String tenantId, String bindingProfileId);
}
