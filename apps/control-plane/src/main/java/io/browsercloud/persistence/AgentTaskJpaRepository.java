package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTaskJpaRepository extends JpaRepository<AgentTaskEntity, String> {

  Optional<AgentTaskEntity> findByTaskIdAndTenantId(String taskId, String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select task from AgentTaskEntity task where task.taskId = :taskId and task.tenantId = :tenantId")
  Optional<AgentTaskEntity> findForUpdate(
      @Param("taskId") String taskId, @Param("tenantId") String tenantId);

  List<AgentTaskEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

  long countByTenantId(String tenantId);
}
