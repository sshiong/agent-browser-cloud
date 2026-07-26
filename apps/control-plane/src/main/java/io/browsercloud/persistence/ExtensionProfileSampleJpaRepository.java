package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtensionProfileSampleJpaRepository
    extends JpaRepository<ExtensionProfileSampleEntity, String> {
  List<ExtensionProfileSampleEntity> findTop1000ByExtensionIdOrderByObservedAtDesc(
      String extensionId);
}
