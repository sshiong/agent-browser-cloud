package io.browsercloud.infrastructure;

import io.browsercloud.persistence.SessionContextEntity;
import io.browsercloud.persistence.SessionContextId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Session Context JPA Repository。 */
@Repository
public interface SessionContextJpaRepository
    extends JpaRepository<SessionContextEntity, SessionContextId> {

  Optional<SessionContextEntity> findTopBySessionIdOrderByContextEpochDesc(String sessionId);

  @Query(
      """
      select context
        from SessionContextEntity context
       where context.sessionId in :sessionIds
         and context.contextEpoch = (
           select max(latest.contextEpoch)
             from SessionContextEntity latest
            where latest.sessionId = context.sessionId
         )
      """)
  List<SessionContextEntity> findLatestBySessionIds(
      @Param("sessionIds") Collection<String> sessionIds);
}
