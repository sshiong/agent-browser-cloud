package io.browsercloud.application;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描过期 Step deadline / Executor lease，并以数据库锁保证单实例恢复。 */
@Component
public class AgentExecutionRecoveryScheduler {

  private final AgentTaskJpaRepository taskRepository;
  private final AgentHumanGovernanceService governanceService;
  private final CoordinatorCommandRoutingService commandRouting;

  public AgentExecutionRecoveryScheduler(
      AgentTaskJpaRepository taskRepository,
      AgentHumanGovernanceService governanceService,
      CoordinatorCommandRoutingService commandRouting) {
    this.taskRepository = taskRepository;
    this.governanceService = governanceService;
    this.commandRouting = commandRouting;
  }

  @Scheduled(fixedDelayString = "${agent.recovery-interval-ms:1000}")
  public void recover() {
    var now = Instant.now();
    taskRepository
        .findRecoverableTaskIds(now, PageRequest.of(0, 50))
        .forEach(
            taskId ->
                taskRepository
                    .findById(taskId)
                    .ifPresent(
                        task ->
                            commandRouting.enqueueAsync(
                                task.getSessionId(),
                                AGENT_RECOVER,
                                "agent-recover:" + taskId + ":" + now.getEpochSecond(),
                                new AgentRecover(taskId, now),
                                Duration.ofMinutes(2))));
    taskRepository
        .findExpiredConfirmationIds(now, PageRequest.of(0, 50))
        .forEach(taskId -> governanceService.expire(taskId, now));
    taskRepository
        .findExpiredHandoffIds(now, PageRequest.of(0, 50))
        .forEach(taskId -> governanceService.expire(taskId, now));
  }
}
