package io.browsercloud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeBuildJpaRepository extends JpaRepository<RuntimeBuildEntity, String> {}
