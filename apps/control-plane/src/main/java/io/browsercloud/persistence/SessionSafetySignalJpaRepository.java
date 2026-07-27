package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionSafetySignalJpaRepository
    extends JpaRepository<SessionSafetySignalEntity, String> {

  Optional<SessionSafetySignalEntity> findBySessionIdAndSignalTypeAndSource(
      String sessionId, String signalType, String source);

  List<SessionSafetySignalEntity> findAllBySessionId(String sessionId);
}
