package io.browsercloud.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionApplicationRebindJpaRepository
    extends JpaRepository<SessionApplicationRebindEntity, String> {

  Optional<SessionApplicationRebindEntity> findByOperationIdAndTenantId(
      String operationId, String tenantId);
}
