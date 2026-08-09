package io.browsercloud.application;

import static io.browsercloud.api.EnterpriseOperationsModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

/** PostgreSQL leased queue and fencing boundary for isolated Runtime Validation Workers. */
@Service
public class RuntimeValidationQueueApplicationService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final EnterpriseOperationsApplicationService enterprise;
  private final Duration claimLease;

  public RuntimeValidationQueueApplicationService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      EnterpriseOperationsApplicationService enterprise,
      @Value("${enterprise.validation-worker.claim-lease-seconds:60}") long claimLeaseSeconds) {
    if (claimLeaseSeconds < 30 || claimLeaseSeconds > 300) {
      throw new IllegalArgumentException(
          "enterprise.validation-worker.claim-lease-seconds must be between 30 and 300");
    }
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.enterprise = enterprise;
    this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
  }

  @Transactional
  public Optional<RuntimeValidationJobClaimView> claim(
      ClaimRuntimeValidationJobRequest request, String workerId) {
    var now = Instant.now();
    expireLeases(now, 50);
    upsertWorker(workerId, request, "ONLINE", null, now);
    var candidates =
        jdbc.queryForList(
            """
            SELECT validation_id
              FROM runtime_validation_jobs
             WHERE state = 'QUEUED'
               AND available_at <= ?
               AND attempt < maximum_attempts
               AND browser_engine = ?
               AND operating_system = ?
               AND architecture = ?
               AND jsonb_exists(CAST(? AS jsonb), browser_version)
               AND CAST(? AS jsonb) @> required_worker_capabilities
             ORDER BY available_at, created_at, validation_id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            request.browserEngine(),
            request.operatingSystem(),
            request.architecture(),
            json(request.browserVersions()),
            json(request.capabilities()));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    var validationId = candidates.getFirst();
    var claimToken = token();
    var leaseExpiresAt = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE runtime_validation_jobs
               SET state = 'CLAIMED', attempt = attempt + 1,
                   claim_owner = ?, claim_epoch = claim_epoch + 1,
                   claim_token_hash = ?, lease_expires_at = ?,
                   last_heartbeat_at = ?, failure_code = NULL, updated_at = ?
             WHERE validation_id = ? AND state = 'QUEUED' AND attempt < maximum_attempts
            """,
            workerId,
            sha256(claimToken),
            sqlTime(leaseExpiresAt),
            sqlTime(now),
            sqlTime(now),
            validationId);
    if (changed != 1) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_CLAIM_FENCED");
    }
    var job = requireJob(validationId);
    appendEvent(
        validationId,
        "CLAIMED",
        "QUEUED",
        "CLAIMED",
        workerId,
        job.claimEpoch(),
        job.attempt(),
        null,
        now);
    upsertWorker(workerId, request, "BUSY", validationId, now);
    return Optional.of(
        new RuntimeValidationJobClaimView(
            claimToken, enterprise.getValidation(validationId), leaseExpiresAt, job.claimEpoch()));
  }

  @Transactional
  public RuntimeValidationJobView start(
      String validationId, RuntimeValidationJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(validationId, request.claimToken(), workerId, now, "CLAIMED");
    updateState(validationId, "CLAIMED", "EXECUTING", now, false, null, null);
    appendEvent(
        validationId,
        "EXECUTION_STARTED",
        "CLAIMED",
        "EXECUTING",
        workerId,
        job.claimEpoch(),
        job.attempt(),
        null,
        now);
    touchWorker(workerId, validationId, "BUSY", now);
    return requireJob(validationId);
  }

  @Transactional
  public RuntimeValidationJobView heartbeat(
      String validationId, RuntimeValidationJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(validationId, request.claimToken(), workerId, now, "EXECUTING");
    var leaseExpiresAt = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE runtime_validation_jobs
               SET lease_expires_at = ?, last_heartbeat_at = ?, updated_at = ?
             WHERE validation_id = ? AND state = 'EXECUTING'
               AND claim_owner = ? AND claim_epoch = ? AND claim_token_hash = ?
            """,
            sqlTime(leaseExpiresAt),
            sqlTime(now),
            sqlTime(now),
            validationId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_HEARTBEAT_FENCED");
    }
    touchWorker(workerId, validationId, "BUSY", now);
    return requireJob(validationId);
  }

  @Transactional
  public RuntimeValidationView complete(
      String validationId, CompleteRuntimeValidationJobRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(validationId, request.claimToken(), workerId, now, "EXECUTING");
    var resultHash = evidenceHash(request.result());
    updateState(validationId, "EXECUTING", "ACKED", now, false, null, resultHash);
    appendEvent(
        validationId,
        "RESULT_ACKED",
        "EXECUTING",
        "ACKED",
        workerId,
        job.claimEpoch(),
        job.attempt(),
        null,
        now);
    return enterprise.completeValidation(validationId, request.result(), workerId);
  }

  @Transactional
  public RuntimeValidationView fail(
      String validationId, FailRuntimeValidationJobRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(validationId, request.claimToken(), workerId, now, null);
    if (request.retryable() && job.attempt() < job.maximumAttempts()) {
      var retryAt = now.plusSeconds(Math.min(60, 5L << Math.min(job.attempt() - 1, 3)));
      var changed =
          jdbc.update(
              """
              UPDATE runtime_validation_jobs
                 SET state = 'QUEUED', available_at = ?, claim_owner = NULL,
                     claim_token_hash = NULL, lease_expires_at = NULL,
                     last_heartbeat_at = NULL, failure_code = ?, updated_at = ?
               WHERE validation_id = ? AND state IN ('CLAIMED', 'EXECUTING')
                 AND claim_owner = ? AND claim_epoch = ?
              """,
              sqlTime(retryAt),
              request.failureCode(),
              sqlTime(now),
              validationId,
              workerId,
              job.claimEpoch());
      if (changed != 1) {
        throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_RETRY_FENCED");
      }
      appendEvent(
          validationId,
          "RETRY_SCHEDULED",
          job.state(),
          "QUEUED",
          workerId,
          job.claimEpoch(),
          job.attempt(),
          request.failureCode(),
          now);
      touchWorker(workerId, null, "ONLINE", now);
      return enterprise.getValidation(validationId);
    }
    failPermanently(job, workerId, request.failureCode(), now, "WORKER_FAILED");
    return enterprise.getValidation(validationId);
  }

  @Transactional
  public int expireLeases(Instant now, int limit) {
    var expired =
        jdbc.queryForList(
            """
            SELECT validation_id
              FROM runtime_validation_jobs
             WHERE state IN ('CLAIMED', 'EXECUTING')
               AND lease_expires_at <= ?
             ORDER BY lease_expires_at, validation_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            Math.max(1, Math.min(limit, 200)));
    for (var validationId : expired) {
      var job = requireJob(validationId);
      var workerId = job.workerId();
      if (job.attempt() < job.maximumAttempts()) {
        var changed =
            jdbc.update(
                """
                UPDATE runtime_validation_jobs
                   SET state = 'QUEUED', available_at = ?, claim_owner = NULL,
                       claim_token_hash = NULL, lease_expires_at = NULL,
                       last_heartbeat_at = NULL, failure_code = 'VALIDATION_WORKER_LEASE_EXPIRED',
                       updated_at = ?
                 WHERE validation_id = ? AND state = ? AND claim_epoch = ?
                """,
                sqlTime(now.plusSeconds(5)),
                sqlTime(now),
                validationId,
                job.state(),
                job.claimEpoch());
        if (changed == 1) {
          appendEvent(
              validationId,
              "LEASE_EXPIRED_REQUEUED",
              job.state(),
              "QUEUED",
              workerId,
              job.claimEpoch(),
              job.attempt(),
              "VALIDATION_WORKER_LEASE_EXPIRED",
              now);
        }
      } else {
        failPermanently(job, workerId, "VALIDATION_WORKER_LEASE_EXHAUSTED", now, "LEASE_EXHAUSTED");
      }
      if (workerId != null) {
        touchWorker(workerId, null, "OFFLINE", now);
      }
    }
    return expired.size();
  }

  private void failPermanently(
      RuntimeValidationJobView job,
      String workerId,
      String failureCode,
      Instant now,
      String eventType) {
    var changed =
        jdbc.update(
            """
            UPDATE runtime_validation_jobs
               SET state = 'FAILED', failure_code = ?, claim_token_hash = NULL,
                   lease_expires_at = NULL, updated_at = ?
             WHERE validation_id = ? AND state = ? AND claim_epoch = ?
            """,
            failureCode,
            sqlTime(now),
            job.validationId(),
            job.state(),
            job.claimEpoch());
    if (changed != 1) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_FAILURE_FENCED");
    }
    appendEvent(
        job.validationId(),
        eventType,
        job.state(),
        "FAILED",
        workerId,
        job.claimEpoch(),
        job.attempt(),
        failureCode,
        now);
    touchWorker(workerId, null, "ONLINE", now);
    enterprise.failValidationExecution(job.validationId(), failureCode, workerId);
  }

  private RuntimeValidationJobView requireActiveClaim(
      String validationId, String claimToken, String workerId, Instant now, String requiredState) {
    var job = requireJobForUpdate(validationId);
    if (requiredState != null && !requiredState.equals(job.state())) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_STATE_MISMATCH");
    }
    if (requiredState == null && !List.of("CLAIMED", "EXECUTING").contains(job.state())) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_STATE_MISMATCH");
    }
    var stored =
        jdbc.queryForObject(
            "SELECT claim_token_hash FROM runtime_validation_jobs WHERE validation_id = ?",
            String.class,
            validationId);
    if (!workerId.equals(job.workerId())
        || stored == null
        || !MessageDigest.isEqual(
            stored.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            sha256(claimToken).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_CLAIM_TOKEN_INVALID");
    }
    if (job.leaseExpiresAt() == null || !job.leaseExpiresAt().isAfter(now)) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_LEASE_EXPIRED");
    }
    return job;
  }

  private void updateState(
      String validationId,
      String fromState,
      String toState,
      Instant now,
      boolean clearClaim,
      String failureCode,
      String resultHash) {
    var changed =
        jdbc.update(
            """
            UPDATE runtime_validation_jobs
               SET state = ?, failure_code = ?, result_hash = COALESCE(?, result_hash),
                   claim_token_hash = CASE WHEN ? THEN NULL ELSE claim_token_hash END,
                   lease_expires_at = CASE WHEN ? THEN NULL ELSE lease_expires_at END,
                   updated_at = ?
             WHERE validation_id = ? AND state = ?
            """,
            toState,
            failureCode,
            resultHash,
            clearClaim,
            clearClaim,
            sqlTime(now),
            validationId,
            fromState);
    if (changed != 1) {
      throw new RuntimeValidationJobRejectedException("VALIDATION_JOB_TRANSITION_FENCED");
    }
  }

  private RuntimeValidationJobView requireJob(String validationId) {
    return loadJob(validationId, false);
  }

  private RuntimeValidationJobView requireJobForUpdate(String validationId) {
    return loadJob(validationId, true);
  }

  private RuntimeValidationJobView loadJob(String validationId, boolean forUpdate) {
    return jdbc
        .query(
            """
            SELECT * FROM runtime_validation_jobs WHERE validation_id = ?
            """
                + (forUpdate ? " FOR UPDATE" : ""),
            (result, row) ->
                new RuntimeValidationJobView(
                    result.getString("validation_id"),
                    result.getString("browser_engine"),
                    result.getString("browser_version"),
                    result.getString("operating_system"),
                    result.getString("architecture"),
                    readCapabilities(result.getString("required_worker_capabilities")),
                    result.getString("state"),
                    result.getInt("attempt"),
                    result.getInt("maximum_attempts"),
                    result.getString("claim_owner"),
                    result.getLong("claim_epoch"),
                    result.getTimestamp("available_at").toInstant(),
                    instant(result.getTimestamp("lease_expires_at")),
                    instant(result.getTimestamp("last_heartbeat_at")),
                    result.getString("failure_code"),
                    result.getString("result_hash"),
                    result.getTimestamp("updated_at").toInstant()),
            validationId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new RuntimeValidationJobNotFoundException(validationId));
  }

  private void upsertWorker(
      String workerId,
      ClaimRuntimeValidationJobRequest request,
      String state,
      String activeValidationId,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO runtime_validation_workers(
          worker_id, browser_engine, browser_versions, operating_system, architecture,
          capabilities, state, active_validation_id, last_seen_at, registered_at
        ) VALUES (?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
        ON CONFLICT(worker_id) DO UPDATE SET
          browser_engine = EXCLUDED.browser_engine,
          browser_versions = EXCLUDED.browser_versions,
          operating_system = EXCLUDED.operating_system,
          architecture = EXCLUDED.architecture,
          capabilities = EXCLUDED.capabilities,
          state = EXCLUDED.state,
          active_validation_id = EXCLUDED.active_validation_id,
          last_seen_at = EXCLUDED.last_seen_at
        """,
        workerId,
        request.browserEngine(),
        json(request.browserVersions()),
        request.operatingSystem(),
        request.architecture(),
        json(request.capabilities()),
        state,
        activeValidationId,
        sqlTime(now),
        sqlTime(now));
  }

  private void touchWorker(String workerId, String activeValidationId, String state, Instant now) {
    if (workerId == null) {
      return;
    }
    jdbc.update(
        """
        UPDATE runtime_validation_workers
           SET state = ?, active_validation_id = ?, last_seen_at = ?
         WHERE worker_id = ?
        """,
        state,
        activeValidationId,
        sqlTime(now),
        workerId);
  }

  private void appendEvent(
      String validationId,
      String eventType,
      String fromState,
      String toState,
      String workerId,
      long claimEpoch,
      int attempt,
      String reasonCode,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO runtime_validation_job_events(
          event_id, validation_id, event_type, from_state, to_state,
          worker_id, claim_epoch, attempt, reason_code, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "vev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
        validationId,
        eventType,
        fromState,
        toState,
        workerId,
        claimEpoch,
        attempt,
        reasonCode,
        sqlTime(now));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "validation worker capabilities cannot be serialized", exception);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Boolean> readCapabilities(String value) {
    try {
      return mapper.readValue(value, Map.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("validation worker capabilities are invalid", exception);
    }
  }

  private String evidenceHash(Object value) {
    try {
      var bytes =
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(value);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("validation worker result cannot be hashed", exception);
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
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Timestamp sqlTime(Instant value) {
    return Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  public static final class RuntimeValidationJobRejectedException extends RuntimeException {
    public RuntimeValidationJobRejectedException(String reason) {
      super(reason);
    }
  }

  public static final class RuntimeValidationJobNotFoundException extends RuntimeException {
    public RuntimeValidationJobNotFoundException(String validationId) {
      super(validationId + " validation job not found");
    }
  }
}
