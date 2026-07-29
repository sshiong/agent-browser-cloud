package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentImportItemJpaRepository
    extends JpaRepository<EnvironmentImportItemEntity, String> {

  List<EnvironmentImportItemEntity> findAllByImportIdAndTenantIdOrderByItemIndexAsc(
      String importId, String tenantId);
}
