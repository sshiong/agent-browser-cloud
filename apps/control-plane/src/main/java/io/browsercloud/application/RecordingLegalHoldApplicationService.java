package io.browsercloud.application;

import static io.browsercloud.application.RecordingLegalHoldNodeGateway.*;

import io.browsercloud.application.AuditApplicationService.AuditRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable version-scoped hold dispatch, with immutable requests across uncertain recovery. */
@Service
public class RecordingLegalHoldApplicationService {
  private final JdbcTemplate jdbc;
  private final BrowserCapacityApplicationService capacity;
  private final RecordingLegalHoldNodeGateway gateway;
  private final AuditApplicationService audit;
  private final TransactionTemplate transaction;

  public RecordingLegalHoldApplicationService(
      JdbcTemplate jdbc,
      BrowserCapacityApplicationService capacity,
      RecordingLegalHoldNodeGateway gateway,
      AuditApplicationService audit,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.capacity = capacity;
    this.gateway = gateway;
    this.audit = audit;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public boolean processNext() {
    var claim = transaction.execute(status -> claimNext());
    if (claim == null || claim.isEmpty()) return false;
    if (claim.get().dispatch()) {
      transaction.executeWithoutResult(status -> execute(claim.get().job()));
    }
    return true;
  }

  private Optional<Claim> claimNext() {
    jdbc.execute("SELECT pg_advisory_xact_lock_shared(1111706692, 134)");
    var candidates =
        jdbc.query(
            """
        SELECT rec.recording_id FROM session_recordings rec
          JOIN recording_cloud_hold_authorities auth USING (tenant_id, session_id, recording_id)
          JOIN recording_cloud_hold_jobs job ON job.operation_id = auth.active_operation_id
         WHERE rec.deleted_at IS NULL AND
           ((job.state IN ('QUEUED','UNKNOWN') AND job.available_at <= now() AND job.attempt < 10)
             OR (job.state = 'EXECUTING' AND job.started_at < now() - interval '2 minutes'))
         ORDER BY job.available_at, job.created_at, job.operation_id
         LIMIT 1 FOR UPDATE OF rec SKIP LOCKED
        """,
            (row, index) -> row.getString(1));
    if (candidates.isEmpty()) return Optional.empty();
    var recording = candidates.getFirst();
    var operation =
        jdbc.queryForObject(
            "SELECT active_operation_id FROM recording_cloud_hold_authorities WHERE recording_id=? FOR UPDATE",
            String.class,
            recording);
    var job =
        jdbc.queryForObject(
            "SELECT * FROM recording_cloud_hold_jobs WHERE operation_id=? FOR UPDATE",
            this::job,
            operation);
    if (job == null) throw new IllegalStateException("RECORDING_HOLD_AUTHORITY_MISSING");
    if (job.attempt() >= 10) {
      unknown(job, "RECORDING_HOLD_RECONCILIATION_EXHAUSTED");
      return Optional.of(new Claim(job, false));
    }
    if (!capacity.nodeHasCapability(
        job.request().nodeId(), "recordingLegalHold", "versioned-cas-journal-v1")) {
      if (job.state().equals("EXECUTING")) unknown(job, "RECORDING_HOLD_CAPABILITY_UNAVAILABLE");
      jdbc.update(
          """
          UPDATE recording_cloud_hold_jobs SET available_at=now()+interval '30 seconds',
            failure_code='RECORDING_HOLD_CAPABILITY_UNAVAILABLE',updated_at=now() WHERE operation_id=?
          """,
          operation);
      return Optional.of(new Claim(job, false));
    }
    // This commits before any RPC. A process crash cannot erase an execution attempt or
    // replace its business epoch, previous proof, mode, manifest or execution budget.
    jdbc.update(
        """
        UPDATE recording_cloud_hold_jobs SET state='EXECUTING',attempt=attempt+1,
          lease_epoch=lease_epoch+1,started_at=now(),failure_code=NULL,updated_at=now()
          WHERE operation_id=?
        """,
        operation);
    if (!job.state().equals("QUEUED")) {
      jdbc.update(
          "UPDATE recording_cloud_hold_authorities SET status='UNKNOWN',updated_at=now() WHERE recording_id=?",
          recording);
    }
    return Optional.of(
        new Claim(
            new Job(job.request(), job.leaseEpoch() + 1, job.attempt() + 1, "EXECUTING"), true));
  }

  private void execute(Job claimed) {
    var request = claimed.request();
    // Same order as policy and ingress: record -> authority -> job -> rollup.
    jdbc.queryForObject(
        "SELECT recording_id FROM session_recordings WHERE recording_id=? FOR UPDATE",
        String.class,
        request.recordingId());
    var operation =
        jdbc.queryForObject(
            "SELECT active_operation_id FROM recording_cloud_hold_authorities WHERE recording_id=? FOR UPDATE",
            String.class,
            request.recordingId());
    if (!request.operationId().equals(operation)) return;
    var current =
        jdbc.queryForObject(
            "SELECT * FROM recording_cloud_hold_jobs WHERE operation_id=? FOR UPDATE",
            this::job,
            operation);
    if (current == null
        || !current.state().equals("EXECUTING")
        || current.leaseEpoch() != claimed.leaseEpoch()) return;
    HoldProof proof;
    try {
      proof = gateway.apply(request);
      validateProof(request, proof);
    } catch (HoldNotDispatchedException exception) {
      // This attempt did not dispatch; an earlier durable attempt may still have done so.
      unknown(current, "RECORDING_HOLD_DISPATCH_UNAVAILABLE");
      return;
    } catch (RuntimeException exception) {
      unknown(current, "RECORDING_HOLD_REMOTE_RESULT_UNKNOWN");
      return;
    }
    var committed =
        jdbc.update(
            """
        UPDATE recording_cloud_hold_jobs SET state='COMMITTED',proof_hash=?,inventory_sha256=?,
          object_version_count=?,completed_at=?,failure_code=NULL,updated_at=now()
          WHERE operation_id=? AND state='EXECUTING' AND lease_epoch=?
        """,
            proof.proofHash(),
            proof.inventorySha256(),
            proof.objectVersionCount(),
            Timestamp.from(proof.completedAt()),
            operation,
            claimed.leaseEpoch());
    if (committed != 1) throw new IllegalStateException("RECORDING_HOLD_LEASE_FENCED");
    var projected =
        jdbc.update(
            """
        UPDATE recording_cloud_hold_authorities auth SET applied_epoch=job.authority_epoch,
          applied_mode=job.mode,proof_hash=job.proof_hash,inventory_sha256=job.inventory_sha256,
          object_version_count=job.object_version_count,completed_at=job.completed_at,
          active_operation_id=NULL,
          status=CASE WHEN auth.desired_epoch=job.authority_epoch THEN 'VERIFIED_'||job.mode ELSE 'PENDING' END,
          updated_at=now() FROM recording_cloud_hold_jobs job
          WHERE auth.active_operation_id=job.operation_id AND job.operation_id=? AND job.state='COMMITTED'
        """,
            operation);
    if (projected != 1) throw new IllegalStateException("RECORDING_HOLD_PROJECTION_FENCED");
    jdbc.queryForObject(
        "SELECT recording_cloud_hold_advance(?)", Object.class, request.recordingId());
    audit.append(
        new AuditRecord(
            request.tenantId(),
            request.sessionId(),
            "RECORDING_CLOUD_HOLD",
            "SYSTEM",
            "recording-hold-worker",
            "RECORDING",
            request.recordingId(),
            "APPLY_" + request.mode(),
            "SUCCEEDED",
            Map.of(
                "operationId",
                operation,
                "authorityEpoch",
                request.authorityEpoch(),
                "proofHash",
                proof.proofHash(),
                "inventorySha256",
                proof.inventorySha256(),
                "objectVersionCount",
                proof.objectVersionCount()),
            null));
  }

  private void unknown(Job job, String code) {
    if (job.attempt() >= 10) code = "RECORDING_HOLD_RECONCILIATION_EXHAUSTED";
    jdbc.update(
        """
        UPDATE recording_cloud_hold_jobs SET state='UNKNOWN',available_at=now()+interval '30 seconds',
          failure_code=?,updated_at=now() WHERE operation_id=? AND state='EXECUTING' AND lease_epoch=?
        """,
        code,
        job.request().operationId(),
        job.leaseEpoch());
    jdbc.update(
        "UPDATE recording_cloud_hold_authorities SET status='UNKNOWN',updated_at=now() WHERE recording_id=?",
        job.request().recordingId());
    audit.append(
        new AuditRecord(
            job.request().tenantId(),
            job.request().sessionId(),
            "RECORDING_CLOUD_HOLD",
            "SYSTEM",
            "recording-hold-worker",
            "RECORDING",
            job.request().recordingId(),
            "RECONCILE",
            "UNKNOWN",
            Map.of(
                "operationId",
                job.request().operationId(),
                "authorityEpoch",
                job.request().authorityEpoch(),
                "failureCode",
                code),
            null));
  }

  private Job job(ResultSet row, int index) throws SQLException {
    return new Job(
        new HoldRequest(
            row.getString("operation_id"),
            row.getLong("authority_epoch"),
            row.getString("node_id"),
            row.getString("tenant_id"),
            row.getString("profile_id"),
            row.getString("session_id"),
            row.getString("recording_id"),
            row.getString("manifest_sha256"),
            row.getLong("manifest_bytes"),
            row.getLong("segment_count"),
            HoldMode.valueOf(row.getString("mode")),
            row.getString("previous_proof_hash"),
            row.getLong("execution_budget_millis")),
        row.getLong("lease_epoch"),
        row.getInt("attempt"),
        row.getString("state"));
  }

  private static void validateProof(HoldRequest request, HoldProof proof) {
    var expected = request.segmentCount() * 2 + 1;
    if (proof == null
        || !request.operationId().equals(proof.operationId())
        || request.authorityEpoch() != proof.authorityEpoch()
        || !request.nodeId().equals(proof.nodeId())
        || !request.recordingId().equals(proof.recordingId())
        || request.mode() != proof.mode()
        || proof.proofHash() == null
        || !proof.proofHash().matches("^[0-9a-f]{64}$")
        || proof.inventorySha256() == null
        || !proof.inventorySha256().matches("^[0-9a-f]{64}$")
        || proof.objectVersionCount() < expected
        || proof.objectVersionCount() > expected * 2
        || proof.completedAt() == null
        || !proof.completedAt().isAfter(Instant.EPOCH)
        || proof.completedAt().isAfter(Instant.now().plusSeconds(30))) {
      throw new HoldResultUnknownException("RECORDING_HOLD_RESPONSE_INVALID");
    }
  }

  private record Job(HoldRequest request, long leaseEpoch, int attempt, String state) {}

  private record Claim(Job job, boolean dispatch) {}
}
