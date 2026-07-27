package io.browsercloud.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionResourceSampleJpaRepository
    extends JpaRepository<SessionResourceSampleEntity, String> {
  List<SessionResourceSampleEntity> findBySessionIdOrderByObservedAtDesc(
      String sessionId, Pageable pageable);

  List<SessionResourceSampleEntity> findBySessionIdAndObservedAtAfterOrderByObservedAtAsc(
      String sessionId, Instant observedAt);
}
