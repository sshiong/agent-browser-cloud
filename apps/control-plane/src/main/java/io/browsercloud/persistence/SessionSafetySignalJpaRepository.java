package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionSafetySignalJpaRepository
    extends JpaRepository<SessionSafetySignalEntity, String> {

  @Query(
      value = "SELECT pg_advisory_xact_lock(hashtextextended(:sessionId, 0))",
      nativeQuery = true)
  void lockSessionObservations(@Param("sessionId") String sessionId);

  Optional<SessionSafetySignalEntity> findBySessionIdAndSignalTypeAndSource(
      String sessionId, String signalType, String source);

  List<SessionSafetySignalEntity> findAllBySessionId(String sessionId);
}
