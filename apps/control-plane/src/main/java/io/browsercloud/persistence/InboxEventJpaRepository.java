package io.browsercloud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Inbox 事件 JPA Repository。 */
@Repository
public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, String> {}
