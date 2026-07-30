package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileJpaRepository extends JpaRepository<ProfileEntity, String> {
  List<ProfileEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

  @Query(
      value =
          """
          SELECT profile.* FROM profiles profile
          WHERE profile.tenant_id = :tenantId
            AND LOWER(
              profile.profile_id || ' ' ||
              profile.name || ' ' ||
              profile.state || ' ' ||
              COALESCE(profile.description, '')
            ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
          ORDER BY profile.updated_at DESC
          """,
      nativeQuery = true)
  List<ProfileEntity> searchAllByTenantId(
      @Param("tenantId") String tenantId, @Param("query") String query, Pageable pageable);
}
