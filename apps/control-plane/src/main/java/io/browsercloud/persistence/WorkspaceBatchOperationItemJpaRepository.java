package io.browsercloud.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceBatchOperationItemJpaRepository
    extends JpaRepository<WorkspaceBatchOperationItemEntity, String> {

  List<WorkspaceBatchOperationItemEntity> findAllByBatchOperationIdOrderByOrdinal(
      String batchOperationId);

  List<WorkspaceBatchOperationItemEntity>
      findAllByBatchOperationIdInOrderByBatchOperationIdAscOrdinalAsc(
          Collection<String> batchOperationIds);
}
