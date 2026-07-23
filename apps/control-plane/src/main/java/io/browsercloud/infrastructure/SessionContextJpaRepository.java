package io.browsercloud.infrastructure;

import io.browsercloud.persistence.SessionContextEntity;
import io.browsercloud.persistence.SessionContextId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Session Context JPA Repository。 */
@Repository
public interface SessionContextJpaRepository
    extends JpaRepository<SessionContextEntity, SessionContextId> {

  Optional<SessionContextEntity> findTopBySessionIdOrderByContextEpochDesc(String sessionId);
}
