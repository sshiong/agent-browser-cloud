package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRecoveryProviderEvidenceJpaRepository
    extends JpaRepository<BusinessRecoveryProviderEvidenceEntity, String> {

  Optional<BusinessRecoveryProviderEvidenceEntity>
      findFirstByTenantIdAndSessionIdAndContractIdAndContractVersionAndContextEpochAndStateVersionAndEvidenceTypeAndEvidenceKeyAndProviderIdOrderByObservedAtDesc(
          String tenantId,
          String sessionId,
          String contractId,
          long contractVersion,
          long contextEpoch,
          long stateVersion,
          String evidenceType,
          String evidenceKey,
          String providerId);

  List<BusinessRecoveryProviderEvidenceEntity> findAllByTenantIdAndSessionIdOrderByCreatedAtDesc(
      String tenantId, String sessionId, Pageable pageable);

  long countByTenantIdAndSessionId(String tenantId, String sessionId);
}
