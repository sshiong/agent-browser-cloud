package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionResourceCostSnapshotJpaRepository
    extends JpaRepository<SessionResourceCostSnapshotEntity, String> {
  List<SessionResourceCostSnapshotEntity> findBySessionIdOrderByObservedAtDesc(
      String sessionId, Pageable pageable);
}
