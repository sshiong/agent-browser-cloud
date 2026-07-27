package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionResourceEventJpaRepository
    extends JpaRepository<SessionResourceEventEntity, String> {
  List<SessionResourceEventEntity> findBySessionIdOrderByOccurredAtDesc(
      String sessionId, Pageable pageable);

  Optional<SessionResourceEventEntity> findFirstBySessionIdAndEventTypeOrderByOccurredAtAsc(
      String sessionId, String eventType);
}
