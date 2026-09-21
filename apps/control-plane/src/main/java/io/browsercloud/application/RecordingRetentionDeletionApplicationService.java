package io.browsercloud.application;

import static io.browsercloud.application.RecordingRetentionDeletionNodeGateway.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.browsercloud.application.AuditApplicationService.AuditRecord;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative, retryable physical deletion of retention-expired Recordings. */
@Service
public class RecordingRetentionDeletionApplicationService {

  private static final String DATA_CLASS = "REMOTE_DESKTOP_RECORDING";
  private static final String CAPABILITY = "recordingRetentionDeletion";
  private static final String CAPABILITY_VERSION = "verified-prefix-tombstone-v1";

  private final JdbcTemplate jdbc;
  private final BrowserCapacityApplicationService capacity;
  private final RecordingRetentionDeletionNodeGateway nodeGateway;
  private final AuditApplicationService audit;
  private final ObjectMapper objectMapper;
  private final int maximumAttempts;

  public RecordingRetentionDeletionApplicationService(
      JdbcTemplate jdbc,
      BrowserCapacityApplicationService capacity,
      RecordingRetentionDeletionNodeGateway nodeGateway,
      AuditApplicationService audit,
      ObjectMapper objectMapper,
      @Value("${recording.retention-deletion.maximum-attempts:10}") int maximumAttempts) {
    if (maximumAttempts < 1 || maximumAttempts > 20) {
      throw new IllegalStateException(
          "Recording retention deletion maximum attempts must be between 1 and 20");
    }
    this.jdbc = jdbc;
    this.capacity = capacity;
    this.nodeGateway = nodeGateway;
    this.audit = audit;
    this.objectMapper = objectMapper;
    this.maximumAttempts = maximumAttempts;
  }

  @Transactional
  public int enqueueDue(int requestedLimit) {
    var limit = Math.max(1, Math.min(requestedLimit, 100));
    var candidates =
        jdbc.query(
            """
            SELECT tenant_id, session_id, recording_id
              FROM session_recordings recording
             WHERE recording.deleted_at IS NULL
               AND recording.legal_hold = FALSE
               AND recording.retention_until <= now()
               AND NOT EXISTS (
                   SELECT 1 FROM recording_retention_deletion_jobs job
                    WHERE job.tenant_id = recording.tenant_id
                      AND job.session_id = recording.session_id
                      AND job.recording_id = recording.recording_id
               )
             ORDER BY recording.retention_until, recording.created_at, recording.recording_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """,
            (result, row) ->
                new DueRecording(
                    result.getString("tenant_id"),
                    result.getString("session_id"),
                    result.getString("recording_id")),
            limit);
    var inserted = 0;
    for (var recording : candidates) {
      inserted +=
          jdbc.update(
              """
              INSERT INTO recording_retention_deletion_jobs(
                  job_id, tenant_id, session_id, recording_id, state,
                  maximum_attempts, available_at, created_at, updated_at)
              VALUES (?, ?, ?, ?, 'QUEUED', ?, now(), now(), now())
              ON CONFLICT (tenant_id, session_id, recording_id) DO NOTHING
              """,
              id("rrd_"),
              recording.tenantId(),
              recording.sessionId(),
              recording.recordingId(),
              maximumAttempts);
    }
    return inserted;
  }

  /**
   * Locks both queue row and Recording authority across the bounded Node call. A concurrent Legal
   * Hold update therefore orders either before deletion (and blocks it) or after the committed
   * physical deletion receipt; it cannot race between policy validation and object deletion.
   */
  @Transactional
  public boolean processNext() {
    var claimed = claimNext();
    if (claimed.isEmpty()) return false;
    var job = claimed.get();
    var attempt = job.attempt() + 1;
    var epoch = job.executionEpoch() + 1;
    jdbc.update(
        """
        UPDATE recording_retention_deletion_jobs
           SET state = 'EXECUTING', attempt = ?, execution_epoch = ?,
               started_at = now(), failure_code = NULL, updated_at = now()
         WHERE job_id = ?
        """,
        attempt,
        epoch,
        job.jobId());
    RecordingDeletionProof proof;
    try {
      if (!capacity.nodeHasCapability(job.nodeId(), CAPABILITY, CAPABILITY_VERSION)) {
        throw new RecordingDeletionNodeUnavailableException(
            "RECORDING_DELETION_CAPABILITY_UNAVAILABLE");
      }
      proof =
          nodeGateway.delete(
              new RecordingDeletionRequest(
                  job.jobId(),
                  epoch,
                  job.nodeId(),
                  job.tenantId(),
                  job.profileId(),
                  job.sessionId(),
                  job.recordingId(),
                  job.manifestSha256(),
                  job.manifestBytes(),
                  job.segmentCount()));
    } catch (RecordingDeletionNodeRejectedException exception) {
      fail(job, attempt, "RECORDING_DELETION_OBJECT_REJECTED", false);
      return true;
    } catch (RecordingDeletionNodeUnavailableException exception) {
      fail(job, attempt, "RECORDING_DELETION_NODE_UNAVAILABLE", true);
      return true;
    } catch (RuntimeException exception) {
      fail(job, attempt, "RECORDING_DELETION_UNEXPECTED_FAILURE", true);
      return true;
    }
    commit(job, epoch, proof);
    return true;
  }

  private Optional<DeletionJob> claimNext() {
    return jdbc
        .query(
            """
            SELECT job.job_id, job.tenant_id, job.session_id, job.recording_id,
                   job.attempt, job.maximum_attempts, job.execution_epoch,
                   recording.node_id, recording.manifest_sha256, recording.manifest_bytes,
                   recording.segment_count, session.profile_id,
                   COALESCE(policy.updated_at, recording.created_at) AS policy_updated_at
              FROM recording_retention_deletion_jobs job
              JOIN session_recordings recording
                ON recording.tenant_id = job.tenant_id
               AND recording.session_id = job.session_id
               AND recording.recording_id = job.recording_id
              JOIN sessions session
                ON session.id = recording.session_id
               AND session.tenant_id = recording.tenant_id
              LEFT JOIN enterprise_retention_policies policy
                ON policy.tenant_id = recording.tenant_id
               AND policy.data_class = 'REMOTE_DESKTOP_RECORDING'
             WHERE job.state IN ('QUEUED', 'RETRY')
               AND job.available_at <= now()
               AND job.attempt < job.maximum_attempts
               AND recording.deleted_at IS NULL
               AND recording.legal_hold = FALSE
               AND recording.retention_until <= now()
             ORDER BY job.available_at, job.created_at, job.job_id
             LIMIT 1
             FOR UPDATE OF job, recording SKIP LOCKED
            """,
            (result, row) ->
                new DeletionJob(
                    result.getString("job_id"),
                    result.getString("tenant_id"),
                    result.getString("session_id"),
                    result.getString("recording_id"),
                    result.getInt("attempt"),
                    result.getInt("maximum_attempts"),
                    result.getLong("execution_epoch"),
                    result.getString("node_id"),
                    result.getString("profile_id"),
                    result.getString("manifest_sha256"),
                    result.getLong("manifest_bytes"),
                    result.getLong("segment_count"),
                    result.getTimestamp("policy_updated_at").toInstant()))
        .stream()
        .findFirst();
  }

  private void commit(DeletionJob job, long epoch, RecordingDeletionProof proof) {
    var now = Instant.now();
    var receiptId = id("del_");
    var receiptHash =
        hash(
            Map.of(
                "receiptId", receiptId,
                "tenantId", job.tenantId(),
                "dataClass", DATA_CLASS,
                "objectId", job.recordingId(),
                "contentDigest", "sha256:" + job.manifestSha256(),
                "policyUpdatedAt", job.policyUpdatedAt().toString(),
                "deletionProofHash", proof.deletionProofHash(),
                "deletedObjectCount", proof.deletedObjectCount(),
                "deletedBy", "recording-retention-worker",
                "deletedAt", now.toString()));
    if (jdbc.update(
            """
            UPDATE session_recordings
               SET deleted_at = ?, deletion_receipt_hash = ?, deletion_proof_hash = ?,
                   deleted_object_count = ?
             WHERE tenant_id = ? AND session_id = ? AND recording_id = ?
               AND deleted_at IS NULL AND legal_hold = FALSE AND retention_until <= ?
            """,
            Timestamp.from(now),
            receiptHash,
            proof.deletionProofHash(),
            proof.deletedObjectCount(),
            job.tenantId(),
            job.sessionId(),
            job.recordingId(),
            Timestamp.from(now))
        != 1) {
      throw new RecordingDeletionNodeRejectedException("RECORDING_DELETION_AUTHORITY_CHANGED");
    }
    jdbc.update(
        """
        INSERT INTO enterprise_retention_deletion_receipts(
          receipt_id, tenant_id, data_class, object_id, content_digest,
          policy_updated_at, receipt_hash, deleted_by, deleted_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        receiptId,
        job.tenantId(),
        DATA_CLASS,
        job.recordingId(),
        "sha256:" + job.manifestSha256(),
        Timestamp.from(job.policyUpdatedAt()),
        receiptHash,
        "recording-retention-worker",
        Timestamp.from(now));
    if (jdbc.update(
            """
            UPDATE recording_retention_deletion_jobs
               SET state = 'COMMITTED', completed_at = ?, failure_code = NULL,
                   deletion_proof_hash = ?, deleted_object_count = ?, updated_at = ?
             WHERE job_id = ? AND state = 'EXECUTING' AND execution_epoch = ?
            """,
            Timestamp.from(now),
            proof.deletionProofHash(),
            proof.deletedObjectCount(),
            Timestamp.from(now),
            job.jobId(),
            epoch)
        != 1) {
      throw new RecordingDeletionNodeRejectedException("RECORDING_DELETION_JOB_FENCED");
    }
    audit.append(
        new AuditRecord(
            job.tenantId(),
            job.sessionId(),
            "RECORDING_RETENTION_DELETION",
            "SYSTEM",
            "recording-retention-worker",
            "RECORDING",
            job.recordingId(),
            "DELETE_EXPIRED_OBJECTS",
            "SUCCEEDED",
            Map.of(
                "receiptHash",
                receiptHash,
                "deletionProofHash",
                proof.deletionProofHash(),
                "deletedObjectCount",
                proof.deletedObjectCount()),
            null));
  }

  private void fail(DeletionJob job, int attempt, String failureCode, boolean retryable) {
    var terminal = !retryable || attempt >= job.maximumAttempts();
    var delay = Duration.ofSeconds(Math.min(300, 1L << Math.min(8, Math.max(0, attempt - 1))));
    jdbc.update(
        """
        UPDATE recording_retention_deletion_jobs
           SET state = ?, available_at = ?, failure_code = ?, updated_at = now()
         WHERE job_id = ? AND state = 'EXECUTING'
        """,
        terminal ? "FAILED" : "RETRY",
        Timestamp.from(Instant.now().plus(delay)),
        failureCode,
        job.jobId());
  }

  private String hash(Object value) {
    try {
      var bytes =
          objectMapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(value);
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Recording deletion receipt cannot be hashed", exception);
    }
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private record DueRecording(String tenantId, String sessionId, String recordingId) {}

  private record DeletionJob(
      String jobId,
      String tenantId,
      String sessionId,
      String recordingId,
      int attempt,
      int maximumAttempts,
      long executionEpoch,
      String nodeId,
      String profileId,
      String manifestSha256,
      long manifestBytes,
      long segmentCount,
      Instant policyUpdatedAt) {}
}
