package io.browsercloud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionSafetyLeaseEventJpaRepository
    extends JpaRepository<SessionSafetyLeaseEventEntity, String> {}
