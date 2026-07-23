package io.browsercloud.infrastructure;

import io.browsercloud.persistence.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Outbox Event JPA Repository。 */
@Repository
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, String> {

  List<OutboxEventEntity> findByPublishedAtIsNullOrderByCreatedAtAsc();

  List<OutboxEventEntity>
      findTop100ByPublishedAtIsNullAndDeadLetteredAtIsNullAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
          String eventType, Instant now);
}
