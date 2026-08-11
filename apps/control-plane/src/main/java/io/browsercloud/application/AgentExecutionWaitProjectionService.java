package io.browsercloud.application;

import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes transient Agent command arbitration without changing the canonical task lifecycle. */
@Service
public class AgentExecutionWaitProjectionService {

  private final AgentTaskJpaRepository tasks;

  public AgentExecutionWaitProjectionService(AgentTaskJpaRepository tasks) {
    this.tasks = tasks;
  }

  @Transactional
  public void waitForHumanInput(
      String taskId, String tenantId, String sessionId, Instant occurredAt) {
    if (taskId == null) return;
    tasks
        .findForUpdate(taskId, tenantId)
        .filter(task -> task.getSessionId().equals(sessionId))
        .ifPresent(task -> task.deferForHumanInput(occurredAt));
  }

  @Transactional
  public void resumeAfterHumanInput(
      String taskId, String tenantId, String sessionId, Instant occurredAt) {
    if (taskId == null) return;
    tasks
        .findForUpdate(taskId, tenantId)
        .filter(task -> task.getSessionId().equals(sessionId))
        .ifPresent(task -> task.resumeAfterHumanInput(occurredAt));
  }
}
