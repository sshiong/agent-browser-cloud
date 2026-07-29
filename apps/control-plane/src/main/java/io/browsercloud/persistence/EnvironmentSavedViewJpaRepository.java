package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnvironmentSavedViewJpaRepository
    extends JpaRepository<EnvironmentSavedViewEntity, String> {

  @Query(
      """
      SELECT view
      FROM EnvironmentSavedViewEntity view
      WHERE view.tenantId = :tenantId
        AND (view.scope = 'WORKSPACE' OR view.ownerActorId = :actorId)
      ORDER BY view.scope ASC, view.updatedAt DESC, view.savedViewId ASC
      """)
  List<EnvironmentSavedViewEntity> findVisible(
      @Param("tenantId") String tenantId, @Param("actorId") String actorId);

  Optional<EnvironmentSavedViewEntity> findBySavedViewIdAndTenantId(
      String savedViewId, String tenantId);
}
