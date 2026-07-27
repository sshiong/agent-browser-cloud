package io.browsercloud.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionMigrationJpaRepository
    extends JpaRepository<SessionMigrationEntity, String> {

  Optional<SessionMigrationEntity> findFirstBySessionIdAndPhaseInOrderByCreatedAtDesc(
      String sessionId, Collection<String> phases);

  Optional<SessionMigrationEntity> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);

  List<SessionMigrationEntity> findAllByPhaseInOrderByUpdatedAtAsc(
      Collection<String> phases, Pageable pageable);
}
