package io.browsercloud.infrastructure;

import io.browsercloud.persistence.BrowserStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrowserStateJpaRepository extends JpaRepository<BrowserStateEntity, String> {}
