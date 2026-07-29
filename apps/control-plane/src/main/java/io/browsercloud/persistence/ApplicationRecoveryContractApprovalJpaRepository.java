package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRecoveryContractApprovalJpaRepository
    extends JpaRepository<ApplicationRecoveryContractApprovalEntity, String> {

  List<ApplicationRecoveryContractApprovalEntity> findAllByTenantIdOrderByRequestedAtDesc(
      String tenantId);

  Optional<ApplicationRecoveryContractApprovalEntity>
      findFirstByTenantIdAndContractIdAndContractVersionOrderByRequestedAtDesc(
          String tenantId, String contractId, long contractVersion);

  Optional<ApplicationRecoveryContractApprovalEntity>
      findByTenantIdAndContractIdAndContractVersionAndState(
          String tenantId, String contractId, long contractVersion, String state);

  boolean existsByTenantIdAndContractIdAndContractVersionAndState(
      String tenantId, String contractId, long contractVersion, String state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select approval from ApplicationRecoveryContractApprovalEntity approval
      where approval.approvalId = :approvalId
        and approval.tenantId = :tenantId
        and approval.applicationId = :applicationId
      """)
  Optional<ApplicationRecoveryContractApprovalEntity> findForUpdate(
      @Param("approvalId") String approvalId,
      @Param("tenantId") String tenantId,
      @Param("applicationId") String applicationId);
}
