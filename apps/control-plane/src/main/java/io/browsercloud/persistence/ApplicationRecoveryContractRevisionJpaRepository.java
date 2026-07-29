package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRecoveryContractRevisionJpaRepository
    extends JpaRepository<
        ApplicationRecoveryContractRevisionEntity, ApplicationRecoveryContractRevisionId> {

  Optional<ApplicationRecoveryContractRevisionEntity>
      findByContractIdAndContractVersionAndTenantIdAndApplicationId(
          String contractId, long contractVersion, String tenantId, String applicationId);

  List<ApplicationRecoveryContractRevisionEntity>
      findAllByContractIdAndTenantIdAndApplicationIdOrderByContractVersionDesc(
          String contractId, String tenantId, String applicationId);
}
