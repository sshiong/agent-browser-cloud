package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRecoveryContractJpaRepository
    extends JpaRepository<ApplicationRecoveryContractEntity, String> {

  Optional<ApplicationRecoveryContractEntity> findByTenantIdAndApplicationId(
      String tenantId, String applicationId);

  List<ApplicationRecoveryContractEntity> findAllByTenantIdOrderByApplicationIdAsc(String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select contract from ApplicationRecoveryContractEntity contract
      where contract.tenantId = :tenantId and contract.applicationId = :applicationId
      """)
  Optional<ApplicationRecoveryContractEntity> findForUpdate(
      @Param("tenantId") String tenantId, @Param("applicationId") String applicationId);
}
