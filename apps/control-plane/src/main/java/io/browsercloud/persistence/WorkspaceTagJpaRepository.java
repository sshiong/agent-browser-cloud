package io.browsercloud.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceTagJpaRepository extends JpaRepository<WorkspaceTagEntity, String> {

  List<WorkspaceTagEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

  List<WorkspaceTagEntity> findAllByTenantIdAndTagIdInOrderByNameAsc(
      String tenantId, Collection<String> tagIds);

  Optional<WorkspaceTagEntity> findByTagIdAndTenantId(String tagId, String tenantId);
}
