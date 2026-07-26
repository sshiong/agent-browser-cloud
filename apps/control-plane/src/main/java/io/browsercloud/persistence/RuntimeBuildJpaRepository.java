package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuntimeBuildJpaRepository extends JpaRepository<RuntimeBuildEntity, String> {
  List<RuntimeBuildEntity> findAllByOrderByCreatedAtDesc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select build from RuntimeBuildEntity build where build.buildId = :buildId")
  Optional<RuntimeBuildEntity> findForUpdate(@Param("buildId") String buildId);
}
