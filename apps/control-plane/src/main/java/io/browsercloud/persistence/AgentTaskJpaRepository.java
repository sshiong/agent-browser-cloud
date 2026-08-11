package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTaskJpaRepository extends JpaRepository<AgentTaskEntity, String> {

  Optional<AgentTaskEntity> findByTaskIdAndTenantId(String taskId, String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select task from AgentTaskEntity task where task.taskId = :taskId and task.tenantId = :tenantId")
  Optional<AgentTaskEntity> findForUpdate(
      @Param("taskId") String taskId, @Param("tenantId") String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from AgentTaskEntity task where task.taskId = :taskId")
  Optional<AgentTaskEntity> findForUpdateByTaskId(@Param("taskId") String taskId);

  @Query(
      """
      select task.taskId from AgentTaskEntity task
      where task.state = 'RUNNING'
        and (
          task.executorLeaseUntil is null
          or task.executorLeaseUntil <= :now
          or task.stepDeadlineAt <= :now
        )
      order by task.updatedAt
      """)
  List<String> findRecoverableTaskIds(@Param("now") Instant now, Pageable pageable);

  @Query(
      """
      select task.taskId from AgentTaskEntity task
      where task.confirmationStatus = 'PENDING'
        and task.confirmationExpiresAt <= :now
      order by task.confirmationExpiresAt
      """)
  List<String> findExpiredConfirmationIds(@Param("now") Instant now, Pageable pageable);

  @Query(
      """
      select task.taskId from AgentTaskEntity task
      where task.handoffStatus = 'PENDING'
        and task.handoffExpiresAt <= :now
      order by task.handoffExpiresAt
      """)
  List<String> findExpiredHandoffIds(@Param("now") Instant now, Pageable pageable);

  List<AgentTaskEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

  long countByTenantId(String tenantId);

  List<AgentTaskEntity> findAllBySessionIdAndState(String sessionId, String state);

  List<AgentTaskEntity> findAllBySessionIdAndStateIn(
      String sessionId, java.util.Collection<String> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select task from AgentTaskEntity task where task.challengeEventId = :eventId and task.tenantId = :tenantId")
  Optional<AgentTaskEntity> findByChallengeEventForUpdate(
      @Param("eventId") String eventId, @Param("tenantId") String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select task from AgentTaskEntity task
      where task.sessionId = :sessionId
        and task.tenantId = :tenantId
        and task.state = 'WAITING_FOR_HUMAN'
        and task.challengeEventId is not null
      order by task.updatedAt desc
      """)
  List<AgentTaskEntity> findWaitingForChallengeBySessionForUpdate(
      @Param("sessionId") String sessionId, @Param("tenantId") String tenantId, Pageable pageable);
}
