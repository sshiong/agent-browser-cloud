package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceBatchOperationJpaRepository
    extends JpaRepository<WorkspaceBatchOperationEntity, String> {

  Optional<WorkspaceBatchOperationEntity> findByTenantIdAndIdempotencyKey(
      String tenantId, String idempotencyKey);

  Optional<WorkspaceBatchOperationEntity> findByBatchOperationIdAndTenantId(
      String batchOperationId, String tenantId);

  List<WorkspaceBatchOperationEntity> findAllByTenantIdOrderByCreatedAtDesc(
      String tenantId, Pageable pageable);

  long countByTenantId(String tenantId);
}
