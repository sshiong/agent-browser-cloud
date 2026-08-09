package io.browsercloud.application;

import static io.browsercloud.api.EnterpriseOperationsModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leased, fenced execution boundary for isolated Recovery GameDay workers. */
@Service
public class RecoveryGameDayQueueApplicationService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final EnterpriseOperationsApplicationService enterprise;
  private final Duration claimLease;

  public RecoveryGameDayQueueApplicationService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      EnterpriseOperationsApplicationService enterprise,
      @Value("${enterprise.gameday-worker.claim-lease-seconds:60}") long claimLeaseSeconds) {
    if (claimLeaseSeconds < 30 || claimLeaseSeconds > 300) {
      throw new IllegalArgumentException(
          "enterprise.gameday-worker.claim-lease-seconds must be between 30 and 300");
    }
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.enterprise = enterprise;
    this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
  }

  @Transactional
  public Optional<RecoveryGameDayJobClaimView> claim(
      ClaimRecoveryGameDayJobRequest request, String workerId) {
    var now = Instant.now();
    expireDeadlines(now, 50);
    expireLeases(now, 50);
    upsertWorker(workerId, request, "ONLINE", null, now);
    var candidates =
        jdbc.queryForList(
            """
            SELECT gameday_id
              FROM recovery_gameday_jobs
             WHERE state IN ('QUEUED', 'RECOVERY_REQUIRED')
               AND available_at <= ?
               AND ((state = 'QUEUED' AND attempt < maximum_attempts)
                 OR (state = 'RECOVERY_REQUIRED'
                     AND recovery_attempt < maximum_recovery_attempts))
               AND jsonb_exists(CAST(? AS jsonb), environment)
               AND jsonb_exists(CAST(? AS jsonb), scenario_code)
               AND CAST(? AS jsonb) @> required_worker_capabilities
             ORDER BY CASE state WHEN 'RECOVERY_REQUIRED' THEN 0 ELSE 1 END,
                      available_at, created_at, gameday_id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            json(request.environments()),
            json(request.scenarioCodes()),
            json(request.capabilities()));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    var gameDayId = candidates.getFirst();
    var before = requireJob(gameDayId);
    var recoveryOnly = "RECOVERY_REQUIRED".equals(before.state());
    var token = token();
    var leaseExpiresAt = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = 'CLAIMED',
                   current_stage = CASE WHEN state = 'RECOVERY_REQUIRED'
                                        THEN 'RECOVERY_REQUIRED' ELSE 'PREPARING' END,
                   attempt = attempt + CASE WHEN state = 'QUEUED' THEN 1 ELSE 0 END,
                   recovery_attempt = recovery_attempt
                       + CASE WHEN state = 'RECOVERY_REQUIRED' THEN 1 ELSE 0 END,
                   claim_owner = ?, claim_epoch = claim_epoch + 1,
                   claim_token_hash = ?, lease_expires_at = ?,
                   last_heartbeat_at = ?, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_epoch = ?
            """,
            workerId,
            sha256(token),
            sqlTime(leaseExpiresAt),
            sqlTime(now),
            sqlTime(now),
            gameDayId,
            before.state(),
            before.claimEpoch());
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_CLAIM_FENCED");
    }
    var job = requireJob(gameDayId);
    jdbc.update(
        """
        UPDATE enterprise_recovery_gamedays
           SET state = 'RUNNING', current_stage = ?, abort_requested = ?
         WHERE gameday_id = ? AND state IN ('QUEUED', 'RUNNING')
        """,
        job.currentStage(),
        job.abortRequested(),
        gameDayId);
    appendEvent(
        job,
        recoveryOnly ? "RECOVERY_CLAIMED" : "CLAIMED",
        before.state(),
        "CLAIMED",
        workerId,
        null,
        now);
    upsertWorker(workerId, request, "BUSY", gameDayId, now);
    return Optional.of(
        new RecoveryGameDayJobClaimView(
            token,
            enterprise.getGameDay(gameDayId),
            leaseExpiresAt,
            job.claimEpoch(),
            recoveryOnly));
  }

  @Transactional
  public RecoveryGameDayJobView start(
      String gameDayId, RecoveryGameDayJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job =
        requireActiveClaim(gameDayId, request.claimToken(), workerId, now, List.of("CLAIMED"));
    var recoveryOnly = "RECOVERY_REQUIRED".equals(job.currentStage());
    var toState = recoveryOnly ? "RECOVERING" : "EXECUTING";
    var toStage = recoveryOnly ? "RECOVERING" : "PREPARING";
    transitionActive(gameDayId, "CLAIMED", toState, toStage, job, now);
    appendEvent(
        requireJob(gameDayId),
        recoveryOnly ? "RECOVERY_STARTED" : "EXECUTION_STARTED",
        "CLAIMED",
        toState,
        workerId,
        null,
        now);
    touchWorker(workerId, gameDayId, "BUSY", now);
    return requireJob(gameDayId);
  }

  @Transactional
  public RecoveryGameDayJobView heartbeat(
      String gameDayId, RecoveryGameDayJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job =
        requireActiveClaim(
            gameDayId, request.claimToken(), workerId, now, List.of("EXECUTING", "RECOVERING"));
    var abortRequested = job.abortRequested() || !job.abortDeadline().isAfter(now);
    var leaseExpiresAt = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET lease_expires_at = ?, last_heartbeat_at = ?,
                   abort_requested = ?, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_owner = ?
               AND claim_epoch = ? AND claim_token_hash = ?
            """,
            sqlTime(leaseExpiresAt),
            sqlTime(now),
            abortRequested,
            sqlTime(now),
            gameDayId,
            job.state(),
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_HEARTBEAT_FENCED");
    }
    if (abortRequested && !job.abortRequested()) {
      jdbc.update(
          "UPDATE enterprise_recovery_gamedays SET abort_requested = TRUE WHERE gameday_id = ?",
          gameDayId);
      appendEvent(
          requireJob(gameDayId),
          "AUTO_ABORT_REQUESTED",
          job.state(),
          job.state(),
          workerId,
          "MAXIMUM_DURATION_REACHED",
          now);
    }
    touchWorker(workerId, gameDayId, "BUSY", now);
    return requireJob(gameDayId);
  }

  @Transactional
  public RecoveryGameDayJobView updateStage(
      String gameDayId, UpdateRecoveryGameDayStageRequest request, String workerId) {
    var now = Instant.now();
    var job =
        requireActiveClaim(
            gameDayId, request.claimToken(), workerId, now, List.of("EXECUTING", "RECOVERING"));
    requireStageTransition(job, request.stage());
    var state = "RECOVERING".equals(request.stage()) ? "RECOVERING" : job.state();
    var faultInjected = job.faultInjected() || "FAULT_INJECTED".equals(request.stage());
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = ?, current_stage = ?, fault_injected = ?, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_owner = ?
               AND claim_epoch = ? AND claim_token_hash = ?
            """,
            state,
            request.stage(),
            faultInjected,
            sqlTime(now),
            gameDayId,
            job.state(),
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_STAGE_FENCED");
    }
    jdbc.update(
        "UPDATE enterprise_recovery_gamedays SET current_stage = ? WHERE gameday_id = ?",
        request.stage(),
        gameDayId);
    var updated = requireJob(gameDayId);
    appendEvent(
        updated, "STAGE_CHANGED", job.state(), updated.state(), workerId, request.stage(), now);
    return updated;
  }

  @Transactional
  public RecoveryGameDayView complete(
      String gameDayId, CompleteRecoveryGameDayJobRequest request, String workerId) {
    var now = Instant.now();
    var job =
        requireActiveClaim(
            gameDayId, request.claimToken(), workerId, now, List.of("EXECUTING", "RECOVERING"));
    if (!Boolean.TRUE.equals(request.result().recoveryConfirmed())) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_RECOVERY_NOT_CONFIRMED");
    }
    if (job.faultInjected() && !"VALIDATING".equals(job.currentStage())) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_VALIDATION_STAGE_REQUIRED");
    }
    var resultHash = evidenceHash(request.result());
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = 'ACKED', recovery_confirmed = TRUE, result_hash = ?,
                   claim_token_hash = NULL, lease_expires_at = NULL, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_owner = ? AND claim_epoch = ?
            """,
            resultHash,
            sqlTime(now),
            gameDayId,
            job.state(),
            workerId,
            job.claimEpoch());
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_RESULT_ACK_FENCED");
    }
    appendEvent(requireJob(gameDayId), "RESULT_ACKED", job.state(), "ACKED", workerId, null, now);
    if (job.abortRequested()) {
      jdbc.update(
          """
          UPDATE recovery_gameday_jobs
             SET state = 'ABORTED', current_stage = 'ABORTED', updated_at = ?
           WHERE gameday_id = ? AND state = 'ACKED'
          """,
          sqlTime(now),
          gameDayId);
      var run = enterprise.failGameDayExecution(gameDayId, "GAMEDAY_ABORTED", true, true, workerId);
      appendEvent(
          requireJob(gameDayId),
          "ABORT_COMMITTED",
          "ACKED",
          "ABORTED",
          workerId,
          "GAMEDAY_ABORTED",
          now);
      touchWorker(workerId, null, "ONLINE", now);
      return run;
    }
    var run = enterprise.completeGameDayExecution(gameDayId, request.result(), workerId);
    var finalState = "PASSED".equals(run.state()) ? "COMMITTED" : "FAILED";
    jdbc.update(
        """
        UPDATE recovery_gameday_jobs
           SET state = ?, current_stage = ?,
               failure_code = CASE WHEN ? = 'FAILED' THEN 'RECOVERY_OBJECTIVES_MISSED'
                                   ELSE NULL END,
               updated_at = ?
         WHERE gameday_id = ? AND state = 'ACKED'
        """,
        finalState,
        finalState,
        finalState,
        sqlTime(now),
        gameDayId);
    appendEvent(
        requireJob(gameDayId),
        "RESULT_COMMITTED",
        "ACKED",
        finalState,
        workerId,
        finalState.equals("FAILED") ? "RECOVERY_OBJECTIVES_MISSED" : null,
        now);
    touchWorker(workerId, null, "ONLINE", now);
    return enterprise.getGameDay(gameDayId);
  }

  @Transactional
  public RecoveryGameDayView fail(
      String gameDayId, FailRecoveryGameDayJobRequest request, String workerId) {
    var now = Instant.now();
    var job =
        requireActiveClaim(
            gameDayId,
            request.claimToken(),
            workerId,
            now,
            List.of("CLAIMED", "EXECUTING", "RECOVERING"));
    if (job.faultInjected() && !request.recoveryConfirmed()) {
      markRecoveryRequired(job, workerId, request.failureCode(), now);
      return enterprise.getGameDay(gameDayId);
    }
    if (job.abortRequested() && request.recoveryConfirmed()) {
      finishFailure(job, workerId, request.failureCode(), true, true, now);
      return enterprise.getGameDay(gameDayId);
    }
    if (request.retryable() && job.attempt() < job.maximumAttempts() && !job.faultInjected()) {
      var retryAt = now.plusSeconds(Math.min(60, 5L << Math.min(job.attempt() - 1, 3)));
      requeue(job, workerId, request.failureCode(), retryAt, now);
      return enterprise.getGameDay(gameDayId);
    }
    finishFailure(job, workerId, request.failureCode(), request.recoveryConfirmed(), false, now);
    return enterprise.getGameDay(gameDayId);
  }

  @Transactional
  public RecoveryGameDayView requestAbort(String gameDayId, String actorId) {
    var now = Instant.now();
    var job = requireJobForUpdate(gameDayId);
    if (List.of("COMMITTED", "FAILED", "ABORTED").contains(job.state())) {
      return enterprise.getGameDay(gameDayId);
    }
    if ("QUEUED".equals(job.state()) && !job.faultInjected()) {
      jdbc.update(
          """
          UPDATE recovery_gameday_jobs
             SET state = 'ABORTED', current_stage = 'ABORTED', abort_requested = TRUE,
                 recovery_confirmed = TRUE, failure_code = 'GAMEDAY_ABORTED_BEFORE_INJECTION',
                 updated_at = ?
           WHERE gameday_id = ? AND state = 'QUEUED'
          """,
          sqlTime(now),
          gameDayId);
      var run =
          enterprise.failGameDayExecution(
              gameDayId, "GAMEDAY_ABORTED_BEFORE_INJECTION", true, true, actorId);
      appendEvent(
          requireJob(gameDayId),
          "ABORTED_BEFORE_INJECTION",
          "QUEUED",
          "ABORTED",
          actorId,
          "PLATFORM_ABORT",
          now);
      return run;
    }
    jdbc.update(
        """
        UPDATE recovery_gameday_jobs
           SET abort_requested = TRUE, updated_at = ?
         WHERE gameday_id = ? AND state NOT IN ('COMMITTED', 'FAILED', 'ABORTED')
        """,
        sqlTime(now),
        gameDayId);
    jdbc.update(
        "UPDATE enterprise_recovery_gamedays SET abort_requested = TRUE WHERE gameday_id = ?",
        gameDayId);
    appendEvent(
        requireJob(gameDayId),
        "ABORT_REQUESTED",
        job.state(),
        job.state(),
        actorId,
        "PLATFORM_ABORT",
        now);
    return enterprise.getGameDay(gameDayId);
  }

  @Transactional
  public int expireLeases(Instant now, int limit) {
    var ids =
        jdbc.queryForList(
            """
            SELECT gameday_id
              FROM recovery_gameday_jobs
             WHERE state IN ('CLAIMED', 'EXECUTING', 'RECOVERING')
               AND lease_expires_at <= ?
             ORDER BY lease_expires_at, gameday_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            boundedLimit(limit));
    for (var gameDayId : ids) {
      var job = requireJob(gameDayId);
      var workerId = job.workerId();
      if (job.faultInjected() && !Boolean.TRUE.equals(job.recoveryConfirmed())) {
        if (job.recoveryAttempt() < job.maximumRecoveryAttempts()) {
          markRecoveryRequired(job, workerId, "GAMEDAY_WORKER_LEASE_EXPIRED", now);
        } else {
          finishFailure(job, workerId, "GAMEDAY_RECOVERY_ATTEMPTS_EXHAUSTED", false, false, now);
        }
      } else if (job.attempt() < job.maximumAttempts() && !job.abortRequested()) {
        requeue(job, workerId, "GAMEDAY_WORKER_LEASE_EXPIRED", now.plusSeconds(5), now);
      } else {
        finishFailure(
            job,
            workerId,
            job.abortRequested()
                ? "GAMEDAY_ABORTED_AFTER_LEASE_EXPIRY"
                : "GAMEDAY_EXECUTION_ATTEMPTS_EXHAUSTED",
            true,
            job.abortRequested(),
            now);
      }
      touchWorker(workerId, null, "OFFLINE", now);
    }
    return ids.size();
  }

  @Transactional
  public int expireDeadlines(Instant now, int limit) {
    var ids =
        jdbc.queryForList(
            """
            SELECT gameday_id
              FROM recovery_gameday_jobs
             WHERE state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'RECOVERY_REQUIRED', 'RECOVERING')
               AND abort_deadline <= ? AND abort_requested = FALSE
             ORDER BY abort_deadline, gameday_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            boundedLimit(limit));
    for (var gameDayId : ids) {
      var job = requireJob(gameDayId);
      if ("QUEUED".equals(job.state()) && !job.faultInjected()) {
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = 'ABORTED', current_stage = 'ABORTED', abort_requested = TRUE,
                   recovery_confirmed = TRUE, failure_code = 'GAMEDAY_AUTO_ABORT_BEFORE_INJECTION',
                   updated_at = ?
             WHERE gameday_id = ? AND state = 'QUEUED'
            """,
            sqlTime(now),
            gameDayId);
        enterprise.failGameDayExecution(
            gameDayId, "GAMEDAY_AUTO_ABORT_BEFORE_INJECTION", true, true, "gameday-reaper");
        appendEvent(
            requireJob(gameDayId),
            "AUTO_ABORTED_BEFORE_INJECTION",
            "QUEUED",
            "ABORTED",
            "gameday-reaper",
            "MAXIMUM_DURATION_REACHED",
            now);
      } else {
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET abort_requested = TRUE, updated_at = ?
             WHERE gameday_id = ? AND abort_requested = FALSE
            """,
            sqlTime(now),
            gameDayId);
        jdbc.update(
            "UPDATE enterprise_recovery_gamedays SET abort_requested = TRUE WHERE gameday_id = ?",
            gameDayId);
        appendEvent(
            requireJob(gameDayId),
            "AUTO_ABORT_REQUESTED",
            job.state(),
            job.state(),
            "gameday-reaper",
            "MAXIMUM_DURATION_REACHED",
            now);
      }
    }
    return ids.size();
  }

  private void requireStageTransition(RecoveryGameDayJobView job, String next) {
    var allowed =
        switch (job.currentStage()) {
          case "PREPARING" -> List.of("INJECTING", "RECOVERING");
          case "INJECTING" -> List.of("FAULT_INJECTED", "RECOVERING");
          case "FAULT_INJECTED" -> List.of("OBSERVING", "RECOVERING");
          case "OBSERVING" -> List.of("RECOVERING");
          case "RECOVERY_REQUIRED" -> List.of("RECOVERING");
          case "RECOVERING" -> List.of("VALIDATING");
          case "VALIDATING" -> List.of("VALIDATING");
          default -> List.of();
        };
    if (!allowed.contains(next)) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_STAGE_TRANSITION_INVALID");
    }
  }

  private void transitionActive(
      String gameDayId,
      String fromState,
      String toState,
      String toStage,
      RecoveryGameDayJobView job,
      Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = ?, current_stage = ?, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_owner = ? AND claim_epoch = ?
            """,
            toState,
            toStage,
            sqlTime(now),
            gameDayId,
            fromState,
            job.workerId(),
            job.claimEpoch());
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_TRANSITION_FENCED");
    }
    jdbc.update(
        "UPDATE enterprise_recovery_gamedays SET current_stage = ? WHERE gameday_id = ?",
        toStage,
        gameDayId);
  }

  private void requeue(
      RecoveryGameDayJobView job,
      String workerId,
      String failureCode,
      Instant availableAt,
      Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = 'QUEUED', current_stage = 'QUEUED', available_at = ?,
                   claim_owner = NULL, claim_token_hash = NULL, lease_expires_at = NULL,
                   last_heartbeat_at = NULL, failure_code = ?, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_epoch = ?
            """,
            sqlTime(availableAt),
            failureCode,
            sqlTime(now),
            job.gameDayId(),
            job.state(),
            job.claimEpoch());
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_RETRY_FENCED");
    }
    jdbc.update(
        "UPDATE enterprise_recovery_gamedays SET current_stage = 'QUEUED' WHERE gameday_id = ?",
        job.gameDayId());
    appendEvent(
        requireJob(job.gameDayId()),
        "RETRY_SCHEDULED",
        job.state(),
        "QUEUED",
        workerId,
        failureCode,
        now);
    touchWorker(workerId, null, "ONLINE", now);
  }

  private void markRecoveryRequired(
      RecoveryGameDayJobView job, String workerId, String failureCode, Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = 'RECOVERY_REQUIRED', current_stage = 'RECOVERY_REQUIRED',
                   abort_requested = TRUE, available_at = ?, claim_owner = NULL,
                   claim_token_hash = NULL, lease_expires_at = NULL,
                   last_heartbeat_at = NULL, failure_code = ?, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_epoch = ?
            """,
            sqlTime(now.plusSeconds(5)),
            failureCode,
            sqlTime(now),
            job.gameDayId(),
            job.state(),
            job.claimEpoch());
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_RECOVERY_FENCE_FAILED");
    }
    jdbc.update(
        """
        UPDATE enterprise_recovery_gamedays
           SET current_stage = 'RECOVERY_REQUIRED', abort_requested = TRUE, failure_code = ?
         WHERE gameday_id = ?
        """,
        failureCode,
        job.gameDayId());
    appendEvent(
        requireJob(job.gameDayId()),
        "RECOVERY_REQUIRED",
        job.state(),
        "RECOVERY_REQUIRED",
        workerId,
        failureCode,
        now);
    touchWorker(workerId, null, "ONLINE", now);
  }

  private void finishFailure(
      RecoveryGameDayJobView job,
      String workerId,
      String failureCode,
      boolean recoveryConfirmed,
      boolean aborted,
      Instant now) {
    var state = aborted && recoveryConfirmed ? "ABORTED" : "FAILED";
    var changed =
        jdbc.update(
            """
            UPDATE recovery_gameday_jobs
               SET state = ?, current_stage = ?, recovery_confirmed = ?, failure_code = ?,
                   claim_token_hash = NULL, lease_expires_at = NULL, updated_at = ?
             WHERE gameday_id = ? AND state = ? AND claim_epoch = ?
            """,
            state,
            state,
            recoveryConfirmed,
            failureCode,
            sqlTime(now),
            job.gameDayId(),
            job.state(),
            job.claimEpoch());
    if (changed != 1) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_FAILURE_FENCED");
    }
    enterprise.failGameDayExecution(
        job.gameDayId(), failureCode, recoveryConfirmed, aborted, workerId);
    appendEvent(
        requireJob(job.gameDayId()),
        aborted ? "ABORTED" : "FAILED",
        job.state(),
        state,
        workerId,
        failureCode,
        now);
    touchWorker(workerId, null, recoveryConfirmed ? "ONLINE" : "OFFLINE", now);
  }

  private RecoveryGameDayJobView requireActiveClaim(
      String gameDayId,
      String claimToken,
      String workerId,
      Instant now,
      List<String> allowedStates) {
    var job = requireJobForUpdate(gameDayId);
    if (!allowedStates.contains(job.state())) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_STATE_MISMATCH");
    }
    var stored =
        jdbc.queryForObject(
            "SELECT claim_token_hash FROM recovery_gameday_jobs WHERE gameday_id = ?",
            String.class,
            gameDayId);
    if (!workerId.equals(job.workerId())
        || stored == null
        || !MessageDigest.isEqual(
            stored.getBytes(StandardCharsets.US_ASCII),
            sha256(claimToken).getBytes(StandardCharsets.US_ASCII))) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_CLAIM_TOKEN_INVALID");
    }
    if (job.leaseExpiresAt() == null || !job.leaseExpiresAt().isAfter(now)) {
      throw new RecoveryGameDayJobRejectedException("GAMEDAY_JOB_LEASE_EXPIRED");
    }
    return job;
  }

  private RecoveryGameDayJobView requireJob(String gameDayId) {
    return loadJob(gameDayId, false);
  }

  private RecoveryGameDayJobView requireJobForUpdate(String gameDayId) {
    return loadJob(gameDayId, true);
  }

  private RecoveryGameDayJobView loadJob(String gameDayId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM recovery_gameday_jobs WHERE gameday_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (result, row) ->
                new RecoveryGameDayJobView(
                    result.getString("gameday_id"),
                    result.getString("scenario_code"),
                    result.getString("environment"),
                    readCapabilities(result.getString("required_worker_capabilities")),
                    result.getString("state"),
                    result.getString("current_stage"),
                    result.getInt("attempt"),
                    result.getInt("maximum_attempts"),
                    result.getInt("recovery_attempt"),
                    result.getInt("maximum_recovery_attempts"),
                    result.getString("claim_owner"),
                    result.getLong("claim_epoch"),
                    result.getTimestamp("available_at").toInstant(),
                    instant(result.getTimestamp("lease_expires_at")),
                    instant(result.getTimestamp("last_heartbeat_at")),
                    result.getTimestamp("abort_deadline").toInstant(),
                    result.getBoolean("abort_requested"),
                    result.getBoolean("fault_injected"),
                    nullableBoolean(result.getObject("recovery_confirmed")),
                    result.getString("failure_code"),
                    result.getString("result_hash"),
                    result.getTimestamp("updated_at").toInstant()),
            gameDayId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new RecoveryGameDayJobNotFoundException(gameDayId));
  }

  private void upsertWorker(
      String workerId,
      ClaimRecoveryGameDayJobRequest request,
      String state,
      String activeGameDayId,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO recovery_gameday_workers(
          worker_id, environments, scenario_codes, capabilities, state,
          active_gameday_id, last_seen_at, registered_at
        ) VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
        ON CONFLICT(worker_id) DO UPDATE SET
          environments = EXCLUDED.environments,
          scenario_codes = EXCLUDED.scenario_codes,
          capabilities = EXCLUDED.capabilities,
          state = EXCLUDED.state,
          active_gameday_id = EXCLUDED.active_gameday_id,
          last_seen_at = EXCLUDED.last_seen_at
        """,
        workerId,
        json(request.environments()),
        json(request.scenarioCodes()),
        json(request.capabilities()),
        state,
        activeGameDayId,
        sqlTime(now),
        sqlTime(now));
  }

  private void touchWorker(String workerId, String activeGameDayId, String state, Instant now) {
    if (workerId == null) {
      return;
    }
    jdbc.update(
        """
        UPDATE recovery_gameday_workers
           SET state = ?, active_gameday_id = ?, last_seen_at = ?
         WHERE worker_id = ?
        """,
        state,
        activeGameDayId,
        sqlTime(now),
        workerId);
  }

  private void appendEvent(
      RecoveryGameDayJobView job,
      String eventType,
      String fromState,
      String toState,
      String workerId,
      String reasonCode,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO recovery_gameday_job_events(
          event_id, gameday_id, event_type, from_state, to_state, stage,
          worker_id, claim_epoch, attempt, reason_code, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "gev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
        job.gameDayId(),
        eventType,
        fromState,
        toState,
        job.currentStage(),
        workerId,
        job.claimEpoch(),
        job.attempt(),
        reasonCode,
        sqlTime(now));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("GameDay worker metadata cannot be serialized", exception);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Boolean> readCapabilities(String value) {
    try {
      return mapper.readValue(value, Map.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("GameDay worker capabilities are invalid", exception);
    }
  }

  private String evidenceHash(Object value) {
    try {
      var bytes =
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(value);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("GameDay result cannot be hashed", exception);
    }
  }

  private static String token() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static int boundedLimit(int limit) {
    return Math.max(1, Math.min(limit, 200));
  }

  private static Timestamp sqlTime(Instant value) {
    return Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static Boolean nullableBoolean(Object value) {
    return value == null ? null : (Boolean) value;
  }

  public static final class RecoveryGameDayJobRejectedException extends RuntimeException {
    public RecoveryGameDayJobRejectedException(String reason) {
      super(reason);
    }
  }

  public static final class RecoveryGameDayJobNotFoundException extends RuntimeException {
    public RecoveryGameDayJobNotFoundException(String gameDayId) {
      super(gameDayId + " Recovery GameDay job not found");
    }
  }
}
