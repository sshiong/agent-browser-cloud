package io.browsercloud.application;

import static io.browsercloud.api.AgentWorkerModels.*;
import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.api.AgentTaskView;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Leased and fenced dispatch boundary for the isolated, data-minimized Agent Worker. */
@Service
public class AgentExecutionWorkerApplicationService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final JdbcTemplate jdbc;
  private final AgentTaskJpaRepository tasks;
  private final AgentApplicationService taskService;
  private final AgentExecutionService executionService;
  private final CoordinatorCommandRoutingService commandRouting;
  private final TransactionTemplate transactions;
  private final boolean enabled;
  private final Duration claimLease;
  private final int maximumAttempts;

  public AgentExecutionWorkerApplicationService(
      JdbcTemplate jdbc,
      AgentTaskJpaRepository tasks,
      AgentApplicationService taskService,
      AgentExecutionService executionService,
      CoordinatorCommandRoutingService commandRouting,
      PlatformTransactionManager transactionManager,
      @Value("${agent.external-worker.enabled:false}") boolean enabled,
      @Value("${agent.external-worker.claim-lease-seconds:60}") long claimLeaseSeconds,
      @Value("${agent.external-worker.maximum-attempts:3}") int maximumAttempts) {
    if (claimLeaseSeconds < 30 || claimLeaseSeconds > 300) {
      throw new IllegalArgumentException(
          "agent.external-worker.claim-lease-seconds must be between 30 and 300");
    }
    if (maximumAttempts < 1 || maximumAttempts > 10) {
      throw new IllegalArgumentException(
          "agent.external-worker.maximum-attempts must be between 1 and 10");
    }
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.taskService = taskService;
    this.executionService = executionService;
    this.commandRouting = commandRouting;
    this.transactions = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
    this.maximumAttempts = maximumAttempts;
  }

  public boolean enabled() {
    return enabled;
  }

  @Transactional
  public AgentTaskView enqueue(String taskId, String tenantId, String idempotencyKey) {
    var task =
        tasks
            .findForUpdate(taskId, tenantId)
            .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
    if (List.of("RUNNING", "WAITING_FOR_HUMAN", "COMPLETED", "FAILED").contains(task.getState())) {
      return taskService.get(taskId, tenantId);
    }
    if ("QUEUED".equals(task.getState())) {
      return taskService.get(taskId, tenantId);
    }
    if (!"PLANNED".equals(task.getState())) {
      throw new AgentExecutionWorkerRejectedException("AGENT_TASK_NOT_PLANNED");
    }
    var now = Instant.now();
    var existing = findByTaskId(taskId);
    if (existing.isPresent()) {
      var job = existing.orElseThrow();
      if (!"WAITING".equals(job.state())) {
        throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_ALREADY_EXISTS");
      }
      jdbc.update(
          """
          UPDATE agent_execution_jobs
             SET state = 'QUEUED', request_idempotency_key = ?, available_at = ?,
                 failure_code = NULL, updated_at = ?
           WHERE job_id = ? AND state = 'WAITING'
          """,
          idempotencyKey,
          sqlTime(now),
          sqlTime(now),
          job.jobId());
      appendEvent(
          job.jobId(), "REQUEUED", "QUEUED", job.attempt(), null, job.claimEpoch(), null, now);
    } else {
      var jobId = id("ajob_");
      jdbc.update(
          """
          INSERT INTO agent_execution_jobs(
            job_id, task_id, tenant_id, session_id, request_idempotency_key,
            protocol_version, state, attempt, maximum_attempts, claim_epoch,
            available_at, created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, 'agent-worker/v1', 'QUEUED', 0, ?, 0, ?, ?, ?)
          """,
          jobId,
          taskId,
          tenantId,
          task.getSessionId(),
          idempotencyKey,
          maximumAttempts,
          sqlTime(now),
          sqlTime(now),
          sqlTime(now));
      appendEvent(jobId, "ENQUEUED", "QUEUED", 0, null, 0, null, now);
    }
    task.queueForExternalWorker(now);
    tasks.save(task);
    return taskService.get(taskId, tenantId);
  }

  @Transactional
  public Optional<AgentExecutionJobClaimView> claim(
      ClaimAgentExecutionJobRequest request, String workerId) {
    if (!Boolean.TRUE.equals(request.capabilities().get("task-drive-v1"))) {
      throw new AgentExecutionWorkerRejectedException("AGENT_WORKER_CAPABILITY_MISSING");
    }
    var now = Instant.now();
    reapExpired(now, 50);
    var candidates =
        jdbc.queryForList(
            """
            SELECT job_id
              FROM agent_execution_jobs
             WHERE state = 'QUEUED' AND available_at <= ? AND attempt < maximum_attempts
             ORDER BY available_at, created_at, job_id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    var jobId = candidates.getFirst();
    var token = token();
    var leaseExpiresAt = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE agent_execution_jobs
               SET state = 'CLAIMED', attempt = attempt + 1, worker_id = ?,
                   claim_epoch = claim_epoch + 1, claim_token_hash = ?, lease_expires_at = ?,
                   failure_code = NULL, updated_at = ?
             WHERE job_id = ? AND state = 'QUEUED' AND attempt < maximum_attempts
            """,
            workerId,
            sha256(token),
            sqlTime(leaseExpiresAt),
            sqlTime(now),
            jobId);
    if (changed != 1) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_CLAIM_FENCED");
    }
    var job = requireJob(jobId);
    appendEvent(jobId, "CLAIMED", "CLAIMED", job.attempt(), workerId, job.claimEpoch(), null, now);
    return Optional.of(
        new AgentExecutionJobClaimView(token, job, leaseExpiresAt, job.claimEpoch()));
  }

  @Transactional
  public AgentExecutionJobView start(
      String jobId, AgentExecutionJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(jobId, request.claimToken(), workerId, now, "CLAIMED");
    var changed =
        jdbc.update(
            """
            UPDATE agent_execution_jobs
               SET state = 'EXECUTING', started_at = COALESCE(started_at, ?), updated_at = ?
             WHERE job_id = ? AND state = 'CLAIMED' AND claim_epoch = ?
            """,
            sqlTime(now),
            sqlTime(now),
            jobId,
            job.claimEpoch());
    if (changed != 1) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_START_FENCED");
    }
    appendEvent(
        jobId, "STARTED", "EXECUTING", job.attempt(), workerId, job.claimEpoch(), null, now);
    return requireJob(jobId);
  }

  @Transactional
  public AgentExecutionJobView heartbeat(
      String jobId, AgentExecutionJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(jobId, request.claimToken(), workerId, now, "EXECUTING");
    var changed =
        jdbc.update(
            """
            UPDATE agent_execution_jobs
               SET lease_expires_at = ?, updated_at = ?
             WHERE job_id = ? AND state = 'EXECUTING' AND worker_id = ?
               AND claim_epoch = ? AND claim_token_hash = ?
            """,
            sqlTime(now.plus(claimLease)),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_HEARTBEAT_FENCED");
    }
    appendEvent(
        jobId, "HEARTBEAT", "EXECUTING", job.attempt(), workerId, job.claimEpoch(), null, now);
    return requireJob(jobId);
  }

  public AgentExecutionJobView drive(
      String jobId, AgentExecutionJobClaimRequest request, String workerId) {
    var preparation =
        transactions.execute(
            status -> prepareDrive(jobId, request.claimToken(), workerId, Instant.now()));
    if (preparation == null) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_PREPARE_FAILED");
    }
    if (preparation.terminalJob() != null) {
      return preparation.terminalJob();
    }
    var result =
        commandRouting.execute(
            preparation.sessionId(),
            preparation.tenantId(),
            AGENT_EXECUTE,
            preparation.idempotencyKey(),
            new AgentExecute(
                preparation.tenantId(), preparation.taskId(), preparation.idempotencyKey()),
            AgentTaskView.class,
            () ->
                executionService.execute(
                    preparation.taskId(), preparation.tenantId(), preparation.idempotencyKey()));
    var projected =
        transactions.execute(
            status -> finishDrive(jobId, request.claimToken(), workerId, result, Instant.now()));
    if (projected == null) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_PROJECT_FAILED");
    }
    return projected;
  }

  private DrivePreparation prepareDrive(
      String jobId, String claimToken, String workerId, Instant now) {
    var current = requireJobForUpdate(jobId);
    if (List.of("WAITING", "COMMITTED", "FAILED").contains(current.state())) {
      return new DrivePreparation(current, null, null, null, null);
    }
    var job = requireActiveClaim(jobId, claimToken, workerId, now, "EXECUTING");
    var tenantId = jobTenant(jobId);
    var task =
        tasks
            .findForUpdate(job.taskId(), tenantId)
            .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
    if ("QUEUED".equals(task.getState())) {
      task.releaseExternalWorkerQueue(now);
      tasks.save(task);
    }
    return new DrivePreparation(
        null, job.taskId(), tenantId, task.getSessionId(), requestIdempotencyKey(jobId));
  }

  private AgentExecutionJobView finishDrive(
      String jobId, String claimToken, String workerId, AgentTaskView result, Instant now) {
    var current = requireJobForUpdate(jobId);
    if (List.of("WAITING", "COMMITTED", "FAILED").contains(current.state())) {
      return current;
    }
    requireActiveClaim(jobId, claimToken, workerId, now, "EXECUTING");
    projectOutcome(jobId, result, now);
    return requireJob(jobId);
  }

  @Transactional
  public AgentExecutionJobView fail(
      String jobId, FailAgentExecutionJobRequest request, String workerId) {
    var current = requireJobForUpdate(jobId);
    if (List.of("WAITING", "COMMITTED", "FAILED").contains(current.state())) {
      return current;
    }
    var now = Instant.now();
    var job = requireActiveClaim(jobId, request.claimToken(), workerId, now, null);
    if (request.retryable() && job.attempt() < job.maximumAttempts()) {
      requeue(job, request.failureCode(), now);
      return requireJob(jobId);
    }
    failPermanently(job, request.failureCode(), now);
    return requireJob(jobId);
  }

  @Scheduled(fixedDelayString = "${agent.external-worker.reaper-interval-ms:15000}")
  @Transactional
  public void reconcile() {
    var now = Instant.now();
    projectWaiting(now);
    reapExpired(now, 100);
  }

  private void projectWaiting(Instant now) {
    var completed =
        jdbc.query(
            """
            SELECT job.job_id, task.state, task.last_error
              FROM agent_execution_jobs job
              JOIN agent_tasks task ON task.task_id = job.task_id
             WHERE job.state = 'WAITING' AND task.state IN ('COMPLETED', 'FAILED')
             ORDER BY job.updated_at, job.job_id
             LIMIT 100
             FOR UPDATE OF job SKIP LOCKED
            """,
            (result, row) ->
                new WaitingProjection(
                    result.getString("job_id"),
                    result.getString("state"),
                    result.getString("last_error")));
    for (var projection : completed) {
      completeJob(
          requireJob(projection.jobId()),
          "COMPLETED".equals(projection.taskState()) ? "COMMITTED" : "FAILED",
          projection.failureCode(),
          now);
    }
  }

  private int reapExpired(Instant now, int limit) {
    var expired =
        jdbc.queryForList(
            """
            SELECT job_id
              FROM agent_execution_jobs
             WHERE state IN ('CLAIMED', 'EXECUTING') AND lease_expires_at <= ?
             ORDER BY lease_expires_at, job_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            Math.max(1, Math.min(limit, 200)));
    for (var jobId : expired) {
      var job = requireJob(jobId);
      var task = tasks.findById(job.taskId()).orElse(null);
      if (task != null
          && List.of("RUNNING", "WAITING_FOR_HUMAN", "PAUSED_BY_RESOURCE_POLICY")
              .contains(task.getState())) {
        moveToWaiting(job, now);
      } else if (task != null && "COMPLETED".equals(task.getState())) {
        completeJob(job, "COMMITTED", null, now);
      } else if (task != null && "FAILED".equals(task.getState())) {
        completeJob(job, "FAILED", task.getLastError(), now);
      } else if (job.attempt() < job.maximumAttempts()) {
        if (task != null && "PLANNED".equals(task.getState())) {
          task.queueForExternalWorker(now);
          tasks.save(task);
        }
        requeue(job, "AGENT_WORKER_LEASE_EXPIRED", now);
      } else {
        failPermanently(job, "AGENT_WORKER_LEASE_EXHAUSTED", now);
      }
    }
    return expired.size();
  }

  private void projectOutcome(String jobId, AgentTaskView task, Instant now) {
    switch (task.state()) {
      case COMPLETED -> completeJob(requireJob(jobId), "COMMITTED", null, now);
      case FAILED -> completeJob(requireJob(jobId), "FAILED", task.lastError(), now);
      case RUNNING, WAITING_FOR_HUMAN, PAUSED_BY_RESOURCE_POLICY ->
          moveToWaiting(requireJob(jobId), now);
      default -> throw new AgentExecutionWorkerRejectedException("AGENT_TASK_DRIVE_NOT_ACCEPTED");
    }
  }

  private void moveToWaiting(AgentExecutionJobView job, Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE agent_execution_jobs
               SET state = 'WAITING', worker_id = NULL, claim_token_hash = NULL,
                   lease_expires_at = NULL, updated_at = ?
             WHERE job_id = ? AND state IN ('CLAIMED', 'EXECUTING') AND claim_epoch = ?
            """,
            sqlTime(now),
            job.jobId(),
            job.claimEpoch());
    if (changed != 1) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_WAIT_FENCED");
    }
    appendEvent(
        job.jobId(), "WAITING", "WAITING", job.attempt(), null, job.claimEpoch(), null, now);
  }

  private void requeue(AgentExecutionJobView job, String failureCode, Instant now) {
    var delay = Math.min(60, 2L << Math.min(Math.max(0, job.attempt() - 1), 4));
    var changed =
        jdbc.update(
            """
            UPDATE agent_execution_jobs
               SET state = 'QUEUED', worker_id = NULL, claim_token_hash = NULL,
                   lease_expires_at = NULL, available_at = ?, failure_code = ?, updated_at = ?
             WHERE job_id = ? AND state IN ('CLAIMED', 'EXECUTING') AND claim_epoch = ?
            """,
            sqlTime(now.plusSeconds(delay)),
            safeCode(failureCode),
            sqlTime(now),
            job.jobId(),
            job.claimEpoch());
    if (changed != 1) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_RETRY_FENCED");
    }
    appendEvent(
        job.jobId(),
        "REQUEUED",
        "QUEUED",
        job.attempt(),
        null,
        job.claimEpoch(),
        safeCode(failureCode),
        now);
  }

  private void failPermanently(AgentExecutionJobView job, String failureCode, Instant now) {
    completeJob(job, "FAILED", safeCode(failureCode), now);
    tasks
        .findForUpdateByTaskId(job.taskId())
        .ifPresent(
            task -> {
              task.failExternalWorkerQueue(safeCode(failureCode), now);
              tasks.save(task);
            });
  }

  private void completeJob(
      AgentExecutionJobView job, String state, String failureCode, Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE agent_execution_jobs
               SET state = ?, worker_id = NULL, claim_token_hash = NULL,
                   lease_expires_at = NULL, failure_code = ?, completed_at = ?, updated_at = ?
             WHERE job_id = ? AND state IN ('CLAIMED', 'EXECUTING', 'WAITING')
            """,
            state,
            failureCode == null ? null : safeCode(failureCode),
            sqlTime(now),
            sqlTime(now),
            job.jobId());
    if (changed != 1) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_COMPLETE_FENCED");
    }
    appendEvent(
        job.jobId(),
        state,
        state,
        job.attempt(),
        null,
        job.claimEpoch(),
        failureCode == null ? null : safeCode(failureCode),
        now);
  }

  private void appendEvent(
      String jobId,
      String eventType,
      String state,
      int attempt,
      String workerId,
      long claimEpoch,
      String failureCode,
      Instant occurredAt) {
    jdbc.update(
        """
        INSERT INTO agent_execution_job_events(
          job_id, event_type, state, attempt, worker_id, claim_epoch, failure_code, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        jobId,
        eventType,
        state,
        attempt,
        workerId,
        claimEpoch,
        failureCode,
        sqlTime(occurredAt));
  }

  private AgentExecutionJobView requireActiveClaim(
      String jobId, String claimToken, String workerId, Instant now, String requiredState) {
    var job = requireJobForUpdate(jobId);
    if (requiredState != null && !requiredState.equals(job.state())) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_STATE_MISMATCH");
    }
    if (requiredState == null && !List.of("CLAIMED", "EXECUTING").contains(job.state())) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_STATE_MISMATCH");
    }
    var stored =
        jdbc.queryForObject(
            "SELECT claim_token_hash FROM agent_execution_jobs WHERE job_id = ?",
            String.class,
            jobId);
    if (!workerId.equals(job.workerId())
        || stored == null
        || !MessageDigest.isEqual(
            stored.getBytes(StandardCharsets.US_ASCII),
            sha256(claimToken).getBytes(StandardCharsets.US_ASCII))) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_CLAIM_TOKEN_INVALID");
    }
    if (job.leaseExpiresAt() == null || !job.leaseExpiresAt().isAfter(now)) {
      throw new AgentExecutionWorkerRejectedException("AGENT_EXECUTION_JOB_LEASE_EXPIRED");
    }
    return job;
  }

  private Optional<AgentExecutionJobView> findByTaskId(String taskId) {
    return jdbc
        .query("SELECT * FROM agent_execution_jobs WHERE task_id = ?", this::job, taskId)
        .stream()
        .findFirst();
  }

  private AgentExecutionJobView requireJob(String jobId) {
    return loadJob(jobId, false);
  }

  private AgentExecutionJobView requireJobForUpdate(String jobId) {
    return loadJob(jobId, true);
  }

  private AgentExecutionJobView loadJob(String jobId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM agent_execution_jobs WHERE job_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            this::job,
            jobId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AgentExecutionWorkerJobNotFoundException(jobId));
  }

  private AgentExecutionJobView job(ResultSet result, int row) throws SQLException {
    return new AgentExecutionJobView(
        result.getString("job_id"),
        result.getString("task_id"),
        result.getString("protocol_version"),
        result.getString("state"),
        result.getInt("attempt"),
        result.getInt("maximum_attempts"),
        result.getString("worker_id"),
        result.getLong("claim_epoch"),
        instant(result.getTimestamp("lease_expires_at")),
        result.getTimestamp("available_at").toInstant(),
        instant(result.getTimestamp("started_at")),
        instant(result.getTimestamp("completed_at")),
        result.getString("failure_code"),
        result.getTimestamp("updated_at").toInstant());
  }

  private String jobTenant(String jobId) {
    return jdbc.queryForObject(
        "SELECT tenant_id FROM agent_execution_jobs WHERE job_id = ?", String.class, jobId);
  }

  private String requestIdempotencyKey(String jobId) {
    return jdbc.queryForObject(
        "SELECT request_idempotency_key FROM agent_execution_jobs WHERE job_id = ?",
        String.class,
        jobId);
  }

  private static String token() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String safeCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{2,127}$")
        ? value
        : "AGENT_WORKER_FAILED";
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private static Timestamp sqlTime(Instant value) {
    return Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private record WaitingProjection(String jobId, String taskState, String failureCode) {}

  private record DrivePreparation(
      AgentExecutionJobView terminalJob,
      String taskId,
      String tenantId,
      String sessionId,
      String idempotencyKey) {}

  public static final class AgentExecutionWorkerRejectedException extends RuntimeException {
    public AgentExecutionWorkerRejectedException(String reason) {
      super(reason);
    }
  }

  public static final class AgentExecutionWorkerJobNotFoundException extends RuntimeException {
    public AgentExecutionWorkerJobNotFoundException(String jobId) {
      super(jobId + " Agent execution job not found");
    }
  }
}
