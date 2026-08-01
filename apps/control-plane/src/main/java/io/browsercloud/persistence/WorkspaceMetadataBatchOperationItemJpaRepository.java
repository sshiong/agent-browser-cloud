package io.browsercloud.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMetadataBatchOperationItemJpaRepository
    extends JpaRepository<WorkspaceMetadataBatchOperationItemEntity, String> {

  List<WorkspaceMetadataBatchOperationItemEntity> findAllByBatchOperationIdOrderByOrdinal(
      String batchOperationId);

  List<WorkspaceMetadataBatchOperationItemEntity>
      findAllByBatchOperationIdInOrderByBatchOperationIdAscOrdinalAsc(
          Collection<String> batchOperationIds);
}
