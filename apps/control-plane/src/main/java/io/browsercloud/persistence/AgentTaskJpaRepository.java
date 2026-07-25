package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskJpaRepository extends JpaRepository<AgentTaskEntity, String> {

  Optional<AgentTaskEntity> findByTaskIdAndTenantId(String taskId, String tenantId);

  List<AgentTaskEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

  long countByTenantId(String tenantId);
}
