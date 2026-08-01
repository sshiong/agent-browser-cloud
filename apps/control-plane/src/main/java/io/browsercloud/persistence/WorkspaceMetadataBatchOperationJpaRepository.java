package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMetadataBatchOperationJpaRepository
    extends JpaRepository<WorkspaceMetadataBatchOperationEntity, String> {

  Optional<WorkspaceMetadataBatchOperationEntity> findByTenantIdAndIdempotencyKey(
      String tenantId, String idempotencyKey);

  Optional<WorkspaceMetadataBatchOperationEntity> findByBatchOperationIdAndTenantId(
      String batchOperationId, String tenantId);

  List<WorkspaceMetadataBatchOperationEntity> findAllByTenantIdOrderByCreatedAtDesc(
      String tenantId, Pageable pageable);

  long countByTenantId(String tenantId);
}
