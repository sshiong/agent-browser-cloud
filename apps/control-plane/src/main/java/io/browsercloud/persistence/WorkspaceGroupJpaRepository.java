package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceGroupJpaRepository extends JpaRepository<WorkspaceGroupEntity, String> {

  List<WorkspaceGroupEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

  Optional<WorkspaceGroupEntity> findByGroupIdAndTenantId(String groupId, String tenantId);
}
