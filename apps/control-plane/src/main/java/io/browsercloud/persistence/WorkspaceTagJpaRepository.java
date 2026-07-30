package io.browsercloud.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceTagJpaRepository extends JpaRepository<WorkspaceTagEntity, String> {

  List<WorkspaceTagEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

  List<WorkspaceTagEntity> findAllByTenantIdAndTagIdInOrderByNameAsc(
      String tenantId, Collection<String> tagIds);

  Optional<WorkspaceTagEntity> findByTagIdAndTenantId(String tagId, String tenantId);

  @Query(
      value =
          """
          SELECT workspace_tag.* FROM workspace_tags workspace_tag
          WHERE workspace_tag.tenant_id = :tenantId
            AND LOWER(
              workspace_tag.tag_id || ' ' ||
              workspace_tag.name || ' ' ||
              COALESCE(workspace_tag.description, '')
            ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
          ORDER BY workspace_tag.updated_at DESC
          """,
      nativeQuery = true)
  List<WorkspaceTagEntity> searchAllByTenantId(
      @Param("tenantId") String tenantId, @Param("query") String query, Pageable pageable);
}
