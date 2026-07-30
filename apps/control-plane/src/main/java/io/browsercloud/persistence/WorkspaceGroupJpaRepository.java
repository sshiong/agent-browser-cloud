package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceGroupJpaRepository extends JpaRepository<WorkspaceGroupEntity, String> {

  List<WorkspaceGroupEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

  Optional<WorkspaceGroupEntity> findByGroupIdAndTenantId(String groupId, String tenantId);

  @Query(
      value =
          """
          SELECT workspace_group.* FROM workspace_groups workspace_group
          WHERE workspace_group.tenant_id = :tenantId
            AND LOWER(
              workspace_group.group_id || ' ' ||
              workspace_group.name || ' ' ||
              COALESCE(workspace_group.description, '')
            ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
          ORDER BY workspace_group.updated_at DESC
          """,
      nativeQuery = true)
  List<WorkspaceGroupEntity> searchAllByTenantId(
      @Param("tenantId") String tenantId, @Param("query") String query, Pageable pageable);
}
