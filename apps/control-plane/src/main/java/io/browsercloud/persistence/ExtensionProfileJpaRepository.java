package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtensionProfileJpaRepository
    extends JpaRepository<ExtensionProfileEntity, String> {
  List<ExtensionProfileEntity> findAllByOrderByExtensionIdAsc();
}
