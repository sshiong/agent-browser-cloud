package io.browsercloud.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRecoveryValidationJpaRepository
    extends JpaRepository<BusinessRecoveryValidationEntity, String> {

  Optional<BusinessRecoveryValidationEntity> findFirstBySessionIdAndTenantIdOrderByEvaluatedAtDesc(
      String sessionId, String tenantId);
}
