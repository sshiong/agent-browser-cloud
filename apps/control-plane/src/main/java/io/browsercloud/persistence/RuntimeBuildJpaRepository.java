package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeBuildJpaRepository extends JpaRepository<RuntimeBuildEntity, String> {
  List<RuntimeBuildEntity> findAllByOrderByCreatedAtDesc();
}
