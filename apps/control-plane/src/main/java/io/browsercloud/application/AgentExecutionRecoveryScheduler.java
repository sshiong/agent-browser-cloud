package io.browsercloud.application;

import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描过期 Step deadline / Executor lease，并以数据库锁保证单实例恢复。 */
@Component
public class AgentExecutionRecoveryScheduler {

  private final AgentTaskJpaRepository taskRepository;
  private final AgentExecutionService executionService;
  private final AgentHumanGovernanceService governanceService;

  public AgentExecutionRecoveryScheduler(
      AgentTaskJpaRepository taskRepository,
      AgentExecutionService executionService,
      AgentHumanGovernanceService governanceService) {
    this.taskRepository = taskRepository;
    this.executionService = executionService;
    this.governanceService = governanceService;
  }

  @Scheduled(fixedDelayString = "${agent.recovery-interval-ms:1000}")
  public void recover() {
    var now = Instant.now();
    taskRepository
        .findRecoverableTaskIds(now, PageRequest.of(0, 50))
        .forEach(taskId -> executionService.recover(taskId, now));
    taskRepository
        .findExpiredConfirmationIds(now, PageRequest.of(0, 50))
        .forEach(taskId -> governanceService.expire(taskId, now));
    taskRepository
        .findExpiredHandoffIds(now, PageRequest.of(0, 50))
        .forEach(taskId -> governanceService.expire(taskId, now));
  }
}
