package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuntimeBuildJpaRepository extends JpaRepository<RuntimeBuildEntity, String> {
  List<RuntimeBuildEntity> findAllByOrderByCreatedAtDesc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select build from RuntimeBuildEntity build where build.buildId = :buildId")
  Optional<RuntimeBuildEntity> findForUpdate(@Param("buildId") String buildId);

  @Query(
      value =
          """
          SELECT build.* FROM runtime_builds build
          WHERE LOWER(
            build.build_id || ' ' ||
            build.engine || ' ' ||
            build.version || ' ' ||
            build.platform || ' ' ||
            build.release_channel
          ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
          ORDER BY build.created_at DESC
          """,
      nativeQuery = true)
  List<RuntimeBuildEntity> searchAll(@Param("query") String query, Pageable pageable);
}
