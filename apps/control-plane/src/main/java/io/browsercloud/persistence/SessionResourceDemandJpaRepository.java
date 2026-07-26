package io.browsercloud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionResourceDemandJpaRepository
    extends JpaRepository<SessionResourceDemandEntity, String> {}
