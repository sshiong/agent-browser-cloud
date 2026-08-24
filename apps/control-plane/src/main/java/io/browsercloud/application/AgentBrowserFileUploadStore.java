package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserFilesModels.FileUploadView;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.SessionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short PostgreSQL transactions around the direct file-byte stream and durable Node command. */
@Service
public class AgentBrowserFileUploadStore {
  private final JdbcTemplate jdbc;
  private final SessionRepository sessions;
  private final BrowserStateRepository browserStates;
  private final OperationRepository operations;
  private final NodeCommandGateway nodeCommands;
  private final AuditApplicationService audit;

  public AgentBrowserFileUploadStore(
      JdbcTemplate jdbc,
      SessionRepository sessions,
      BrowserStateRepository browserStates,
      OperationRepository operations,
      NodeCommandGateway nodeCommands,
      AuditApplicationService audit) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.browserStates = browserStates;
    this.operations = operations;
    this.nodeCommands = nodeCommands;
    this.audit = audit;
  }

  @Transactional
  public UploadRecord claim(UploadClaim claim) {
    var session = sessions.requireForUpdate(claim.sessionId());
    if (!session.tenantId().equals(claim.tenantId())) {
      throw new SessionNotFoundException(claim.sessionId());
    }
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new FileUploadRejectedException("SESSION_NOT_RUNNING");
    }
    var existing =
        findByIdempotencyForUpdate(claim.tenantId(), claim.sessionId(), claim.idempotencyKey());
    if (existing != null) {
      if (!existing.requestHash().equals(claim.requestHash())
          || !existing.actorId().equals(claim.actorId())) {
        throw new FileUploadRejectedException("AGENT_FILE_IDEMPOTENCY_CONFLICT");
      }
      return existing;
    }
    if (session.nodeId() == null || session.nodeId().isBlank()) {
      throw new FileUploadRejectedException("SESSION_NODE_UNAVAILABLE");
    }
    var snapshot =
        browserStates
            .find(claim.sessionId())
            .filter(value -> value.tenantId().equals(claim.tenantId()))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .orElseThrow(() -> new FileUploadRejectedException("BROWSER_STATE_UNAVAILABLE"));
    var state = snapshot.state();
    if (state.stateVersion() != claim.baseStateVersion()
        || !state.stateHash().equalsIgnoreCase(claim.baseContentHash())
        || state.targetRevision() != claim.targetRevision()) {
      throw new FileUploadRejectedException("STATE_STALE");
    }
    var target =
        state.targets().stream()
            .filter(value -> value.targetRef().equals(claim.targetRef()))
            .findFirst()
            .orElseThrow(() -> new FileUploadRejectedException("FILE_INPUT_TARGET_NOT_FOUND"));
    if (!target.enabled() || !"file".equalsIgnoreCase(target.controlType())) {
      throw new FileUploadRejectedException("FILE_INPUT_TARGET_INVALID");
    }
    operations.ensureNoActiveOperation(session.sessionId());
    var operation =
        OperationFactory.agentFileUpload(
            session, claim.actorId(), operations.nextOperationEpoch(session.sessionId()));
    var now = Instant.now();
    jdbc.update(
        """
        INSERT INTO agent_browser_file_uploads (
          upload_id, tenant_id, session_id, actor_id, idempotency_key, request_hash, request_id,
          operation_id, node_id, coordinator_term, context_epoch, operation_epoch, target_ref,
          target_revision, base_state_version, base_content_hash, filename, mime_type,
          content_sha256, content_bytes, state, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STAGING', ?, ?)
        """,
        claim.uploadId(),
        claim.tenantId(),
        claim.sessionId(),
        claim.actorId(),
        claim.idempotencyKey(),
        claim.requestHash(),
        claim.requestId(),
        operation.operationId(),
        session.nodeId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        operation.operationEpoch(),
        claim.targetRef(),
        claim.targetRevision(),
        claim.baseStateVersion(),
        claim.baseContentHash(),
        claim.filename(),
        claim.mimeType(),
        claim.contentSha256(),
        claim.contentBytes(),
        Timestamp.from(now),
        Timestamp.from(now));
    operations.insert(operation);
    audit.append(
        new AuditApplicationService.AuditRecord(
            claim.tenantId(),
            claim.sessionId(),
            "AGENT_BROWSER_FILE_UPLOAD",
            "AGENT",
            claim.actorId(),
            "FILE_UPLOAD",
            claim.uploadId(),
            "STAGE",
            "ACCEPTED",
            Map.of(
                "operationId", operation.operationId(),
                "targetRef", claim.targetRef(),
                "filename", claim.filename(),
                "mimeType", claim.mimeType(),
                "contentSha256", claim.contentSha256(),
                "contentBytes", claim.contentBytes()),
            claim.requestId()));
    return requireForUpdate(claim.uploadId(), claim.tenantId());
  }

  @Transactional
  public UploadRecord dispatch(String uploadId, String tenantId) {
    var upload = requireForUpdate(uploadId, tenantId);
    if (!"STAGING".equals(upload.state())) return upload;
    var session = sessions.requireForUpdate(upload.sessionId());
    if (!tenantId.equals(session.tenantId())
        || !upload.nodeId().equals(session.nodeId())
        || upload.coordinatorTerm() != session.coordinatorTerm()
        || upload.contextEpoch() != session.contextEpoch()) {
      return failLocked(upload, "SESSION_FENCE_STALE", upload.requestId());
    }
    var operation =
        operations
            .findActive(upload.sessionId())
            .filter(value -> value.operationId().equals(upload.operationId()))
            .filter(value -> value.operationEpoch() == upload.operationEpoch())
            .filter(value -> value.mode() == OperationMode.AGENT_INTERACTIVE)
            .filter(value -> value.allowedCapabilities().contains("browser.file.upload"))
            .orElseThrow(() -> new FileUploadRejectedException("AGENT_FILE_OPERATION_STALE"));
    operations.transitionPhase(
        operation.operationId(), OperationPhase.PREPARING, OperationPhase.EXECUTING);
    jdbc.update(
        "UPDATE agent_browser_file_uploads SET state = 'EXECUTING', updated_at = ?, version = version + 1 WHERE upload_id = ? AND state = 'STAGING'",
        Timestamp.from(Instant.now()),
        uploadId);
    nodeCommands.send(
        NodeCommands.agentFileUpload(
            session,
            operation,
            upload.uploadId(),
            upload.targetRef(),
            upload.targetRevision(),
            upload.baseStateVersion(),
            upload.baseContentHash(),
            upload.filename(),
            upload.mimeType(),
            upload.contentSha256(),
            upload.contentBytes()));
    return requireForUpdate(uploadId, tenantId);
  }

  @Transactional
  public boolean stateUpdated(NodeEventReceived envelope, NodeEvent.StateUpdated state) {
    if (!"AGENT_FILE_UPLOAD".equals(state.snapshotKind())) return false;
    var upload = requireForUpdate(state.requestedRootRef(), envelope.tenantId());
    requireEnvelope(upload, envelope);
    if ("COMMITTED".equals(upload.state())) return true;
    if (!"EXECUTING".equals(upload.state())
        || state.stateVersion() <= upload.baseStateVersion()
        || !java.util.Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      failLocked(upload, "POST_UPLOAD_STATE_INVALID", envelope.eventId());
      return true;
    }
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE agent_browser_file_uploads
        SET state = 'COMMITTED', state_version_after = ?, completed_at = ?, updated_at = ?,
            version = version + 1
        WHERE upload_id = ? AND state = 'EXECUTING'
        """,
        state.stateVersion(),
        Timestamp.from(now),
        Timestamp.from(now),
        upload.uploadId());
    operations.transitionPhase(
        upload.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
    operations.transition(upload.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
    audit.append(
        new AuditApplicationService.AuditRecord(
            upload.tenantId(),
            upload.sessionId(),
            "AGENT_BROWSER_FILE_UPLOAD",
            "NODE",
            "browser-node",
            "FILE_UPLOAD",
            upload.uploadId(),
            "SET_FILE_INPUT_FILES",
            "COMMITTED",
            Map.of(
                "operationId", upload.operationId(),
                "filename", upload.filename(),
                "mimeType", upload.mimeType(),
                "contentSha256", upload.contentSha256(),
                "contentBytes", upload.contentBytes(),
                "stateVersion", state.stateVersion()),
            envelope.eventId()));
    return true;
  }

  @Transactional
  public void failed(NodeEventReceived envelope, NodeEvent.AgentFileUploadFailed failure) {
    var upload = requireForUpdate(failure.uploadId(), envelope.tenantId());
    requireEnvelope(upload, envelope);
    if (!"COMMITTED".equals(upload.state()) && !"FAILED".equals(upload.state())) {
      failLocked(upload, failure.errorCode(), envelope.eventId());
    }
  }

  @Transactional
  public UploadRecord fail(String uploadId, String tenantId, String errorCode, String requestId) {
    var upload = requireForUpdate(uploadId, tenantId);
    if ("COMMITTED".equals(upload.state()) || "FAILED".equals(upload.state())) return upload;
    return failLocked(upload, errorCode, requestId);
  }

  @Transactional(readOnly = true)
  public FileUploadView get(String uploadId, String tenantId) {
    return toView(require(uploadId, tenantId));
  }

  private UploadRecord failLocked(UploadRecord upload, String errorCode, String requestId) {
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE agent_browser_file_uploads
        SET state = 'FAILED', error_code = ?, completed_at = ?, updated_at = ?, version = version + 1
        WHERE upload_id = ? AND state IN ('STAGING', 'EXECUTING')
        """,
        errorCode,
        Timestamp.from(now),
        Timestamp.from(now),
        upload.uploadId());
    operations
        .findActive(upload.sessionId())
        .filter(value -> value.operationId().equals(upload.operationId()))
        .ifPresent(
            value ->
                operations.transition(
                    value.operationId(), OperationState.ACTIVE, OperationState.ABORTED));
    audit.append(
        new AuditApplicationService.AuditRecord(
            upload.tenantId(),
            upload.sessionId(),
            "AGENT_BROWSER_FILE_UPLOAD",
            "SYSTEM",
            "control-plane",
            "FILE_UPLOAD",
            upload.uploadId(),
            "SET_FILE_INPUT_FILES",
            errorCode,
            Map.of(
                "operationId", upload.operationId(),
                "filename", upload.filename(),
                "mimeType", upload.mimeType(),
                "contentSha256", upload.contentSha256(),
                "contentBytes", upload.contentBytes()),
            requestId));
    return requireForUpdate(upload.uploadId(), upload.tenantId());
  }

  private void requireEnvelope(UploadRecord upload, NodeEventReceived envelope) {
    if (!upload.sessionId().equals(envelope.sessionId())
        || upload.coordinatorTerm() != envelope.coordinatorTerm()
        || upload.contextEpoch() != envelope.contextEpoch()
        || upload.operationEpoch() != envelope.operationEpoch()) {
      throw new FileUploadRejectedException("AGENT_FILE_EVENT_FENCE_STALE");
    }
  }

  private UploadRecord findByIdempotencyForUpdate(
      String tenantId, String sessionId, String idempotencyKey) {
    var values =
        jdbc.query(
            "SELECT * FROM agent_browser_file_uploads WHERE tenant_id = ? AND session_id = ? AND idempotency_key = ? FOR UPDATE",
            AgentBrowserFileUploadStore::map,
            tenantId,
            sessionId,
            idempotencyKey);
    return values.isEmpty() ? null : values.getFirst();
  }

  private UploadRecord requireForUpdate(String uploadId, String tenantId) {
    var values =
        jdbc.query(
            "SELECT * FROM agent_browser_file_uploads WHERE upload_id = ? AND tenant_id = ? FOR UPDATE",
            AgentBrowserFileUploadStore::map,
            uploadId,
            tenantId);
    if (values.isEmpty()) throw new FileUploadNotFoundException();
    return values.getFirst();
  }

  private UploadRecord require(String uploadId, String tenantId) {
    var values =
        jdbc.query(
            "SELECT * FROM agent_browser_file_uploads WHERE upload_id = ? AND tenant_id = ?",
            AgentBrowserFileUploadStore::map,
            uploadId,
            tenantId);
    if (values.isEmpty()) throw new FileUploadNotFoundException();
    return values.getFirst();
  }

  private static UploadRecord map(ResultSet row, int ignored) throws SQLException {
    return new UploadRecord(
        row.getString("upload_id"),
        row.getString("tenant_id"),
        row.getString("session_id"),
        row.getString("actor_id"),
        row.getString("request_hash"),
        row.getString("request_id"),
        row.getString("operation_id"),
        row.getString("node_id"),
        row.getLong("coordinator_term"),
        row.getLong("context_epoch"),
        row.getLong("operation_epoch"),
        row.getString("target_ref"),
        row.getLong("target_revision"),
        row.getLong("base_state_version"),
        row.getString("base_content_hash"),
        row.getString("filename"),
        row.getString("mime_type"),
        row.getString("content_sha256"),
        row.getLong("content_bytes"),
        row.getString("state"),
        row.getString("error_code"),
        nullableLong(row, "state_version_after"),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant(),
        nullableInstant(row, "completed_at"));
  }

  private static Long nullableLong(ResultSet row, String column) throws SQLException {
    var value = row.getLong(column);
    return row.wasNull() ? null : value;
  }

  private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
    var value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public static FileUploadView toView(UploadRecord value) {
    return new FileUploadView(
        value.uploadId(),
        value.operationId(),
        value.sessionId(),
        value.targetRef(),
        value.filename(),
        value.mimeType(),
        value.contentSha256(),
        value.contentBytes(),
        value.state(),
        value.errorCode(),
        value.stateVersionAfter(),
        value.requestId(),
        value.createdAt(),
        value.updatedAt(),
        value.completedAt());
  }

  public record UploadClaim(
      String uploadId,
      String tenantId,
      String sessionId,
      String actorId,
      String idempotencyKey,
      String requestHash,
      String requestId,
      String targetRef,
      long targetRevision,
      long baseStateVersion,
      String baseContentHash,
      String filename,
      String mimeType,
      String contentSha256,
      long contentBytes) {}

  public record UploadRecord(
      String uploadId,
      String tenantId,
      String sessionId,
      String actorId,
      String requestHash,
      String requestId,
      String operationId,
      String nodeId,
      long coordinatorTerm,
      long contextEpoch,
      long operationEpoch,
      String targetRef,
      long targetRevision,
      long baseStateVersion,
      String baseContentHash,
      String filename,
      String mimeType,
      String contentSha256,
      long contentBytes,
      String state,
      String errorCode,
      Long stateVersionAfter,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}

  public static final class FileUploadRejectedException extends RuntimeException {
    public FileUploadRejectedException(String code) {
      super(code);
    }
  }

  public static final class FileUploadNotFoundException extends RuntimeException {}
}
