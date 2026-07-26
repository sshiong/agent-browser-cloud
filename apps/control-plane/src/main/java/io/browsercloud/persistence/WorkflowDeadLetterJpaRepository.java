package io.browsercloud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowDeadLetterJpaRepository
    extends JpaRepository<WorkflowDeadLetterEntity, String> {}
