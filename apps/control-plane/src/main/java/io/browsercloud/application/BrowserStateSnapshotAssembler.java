package io.browsercloud.application;

import com.google.protobuf.InvalidProtocolBufferException;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.infrastructure.NodeEventMapper;
import io.browsercloud.proto.node.v1.BrowserStateEvent;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative, bounded Full Browser State snapshot assembler. */
@Service
public class BrowserStateSnapshotAssembler {

  private static final int MAX_SNAPSHOT_BYTES = 512 * 1024;

  private final JdbcTemplate jdbc;
  private final NodeEventMapper mapper;

  public BrowserStateSnapshotAssembler(JdbcTemplate jdbc, NodeEventMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<NodeEvent.StateUpdated> accept(NodeEventReceived command) {
    return switch (command.event()) {
      case NodeEvent.StateSnapshotBegin begin -> acceptBegin(command, begin);
      case NodeEvent.StateSnapshotChunk chunk -> acceptChunk(command, chunk);
      case NodeEvent.StateSnapshotCommit commit -> acceptCommit(command, commit);
      default -> Optional.empty();
    };
  }

  private Optional<NodeEvent.StateUpdated> acceptBegin(
      NodeEventReceived command, NodeEvent.StateSnapshotBegin begin) {
    var existing = findForUpdate(begin.snapshotId());
    if (existing.isPresent()) {
      requireMatchingEnvelope(command, existing.orElseThrow());
      if (!manifestMatches(
          existing.orElseThrow(), begin.totalChunks(), begin.totalBytes(), begin.payloadSha256())) {
        reject(begin.snapshotId());
      }
      return Optional.empty();
    }

    var cancelled =
        jdbc.queryForList(
            """
            UPDATE browser_state_snapshot_streams
               SET status = 'CANCELLED', updated_at = now()
             WHERE session_id = ? AND context_epoch = ?
               AND status IN ('RECEIVING', 'COMMIT_RECEIVED')
            RETURNING snapshot_id
            """,
            String.class,
            command.sessionId(),
            command.contextEpoch());
    cancelled.forEach(
        snapshotId ->
            jdbc.update(
                "DELETE FROM browser_state_snapshot_chunks WHERE snapshot_id = ?", snapshotId));
    jdbc.update(
        """
        INSERT INTO browser_state_snapshot_streams (
            snapshot_id, tenant_id, session_id, coordinator_term, context_epoch, operation_epoch,
            state_version, target_revision, total_chunks, total_bytes, payload_sha256, snapshot_kind)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        begin.snapshotId(),
        command.tenantId(),
        command.sessionId(),
        command.coordinatorTerm(),
        command.contextEpoch(),
        command.operationEpoch(),
        begin.stateVersion(),
        begin.targetRevision(),
        begin.totalChunks(),
        begin.totalBytes(),
        begin.payloadSha256(),
        begin.snapshotKind());
    return Optional.empty();
  }

  private Optional<NodeEvent.StateUpdated> acceptChunk(
      NodeEventReceived command, NodeEvent.StateSnapshotChunk chunk) {
    var manifest = requireManifest(command, chunk.snapshotId());
    if (manifest.terminal()) {
      return Optional.empty();
    }
    if (!manifestMatches(
        manifest, chunk.totalChunks(), manifest.totalBytes(), manifest.payloadSha256())) {
      reject(chunk.snapshotId());
      return Optional.empty();
    }
    jdbc.update(
        """
        INSERT INTO browser_state_snapshot_chunks (
            snapshot_id, chunk_index, total_chunks, chunk_bytes, chunk_sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (snapshot_id, chunk_index) DO NOTHING
        """,
        chunk.snapshotId(),
        chunk.chunkIndex(),
        chunk.totalChunks(),
        chunk.data().length,
        chunk.chunkSha256(),
        chunk.data());
    var persisted =
        jdbc.query(
            """
            SELECT chunk_sha256, payload
              FROM browser_state_snapshot_chunks
             WHERE snapshot_id = ? AND chunk_index = ?
            """,
            resultSet -> {
              if (!resultSet.next()) {
                return null;
              }
              return new Chunk(chunk.chunkIndex(), resultSet.getString(1), resultSet.getBytes(2));
            },
            chunk.snapshotId(),
            chunk.chunkIndex());
    if (persisted == null
        || !persisted.sha256().equals(chunk.chunkSha256())
        || !MessageDigest.isEqual(persisted.payload(), chunk.data())) {
      reject(manifest.snapshotId());
      return Optional.empty();
    }
    jdbc.update(
        "UPDATE browser_state_snapshot_streams SET updated_at = now() WHERE snapshot_id = ?",
        chunk.snapshotId());
    return manifest.status().equals("COMMIT_RECEIVED") ? tryFinalize(manifest) : Optional.empty();
  }

  private Optional<NodeEvent.StateUpdated> acceptCommit(
      NodeEventReceived command, NodeEvent.StateSnapshotCommit commit) {
    var manifest = requireManifest(command, commit.snapshotId());
    if (manifest.terminal()) {
      return Optional.empty();
    }
    if (!manifestMatches(
        manifest, commit.totalChunks(), commit.totalBytes(), commit.payloadSha256())) {
      reject(commit.snapshotId());
      return Optional.empty();
    }
    jdbc.update(
        """
        UPDATE browser_state_snapshot_streams
           SET status = 'COMMIT_RECEIVED', updated_at = now()
         WHERE snapshot_id = ? AND status = 'RECEIVING'
        """,
        commit.snapshotId());
    return tryFinalize(manifest.withStatus("COMMIT_RECEIVED"));
  }

  private Optional<NodeEvent.StateUpdated> tryFinalize(Manifest manifest) {
    var chunks =
        jdbc.query(
            """
            SELECT chunk_index, chunk_sha256, payload
              FROM browser_state_snapshot_chunks
             WHERE snapshot_id = ?
             ORDER BY chunk_index
            """,
            (resultSet, rowNumber) ->
                new Chunk(
                    resultSet.getInt("chunk_index"),
                    resultSet.getString("chunk_sha256"),
                    resultSet.getBytes("payload")),
            manifest.snapshotId());
    if (chunks.size() < manifest.totalChunks()) {
      return Optional.empty();
    }
    if (chunks.size() != manifest.totalChunks()) {
      return rejectAndEmpty(manifest.snapshotId());
    }

    var assembled = new ByteArrayOutputStream((int) manifest.totalBytes());
    for (int index = 0; index < chunks.size(); index++) {
      var chunk = chunks.get(index);
      if (chunk.index() != index || !sha256(chunk.payload()).equals(chunk.sha256())) {
        return rejectAndEmpty(manifest.snapshotId());
      }
      if (assembled.size() + chunk.payload().length > MAX_SNAPSHOT_BYTES) {
        return rejectAndEmpty(manifest.snapshotId());
      }
      assembled.writeBytes(chunk.payload());
    }
    var payload = assembled.toByteArray();
    if (payload.length != manifest.totalBytes()
        || !sha256(payload).equals(manifest.payloadSha256())) {
      return rejectAndEmpty(manifest.snapshotId());
    }

    final BrowserStateEvent protobuf;
    try {
      protobuf = BrowserStateEvent.parseFrom(payload);
    } catch (InvalidProtocolBufferException exception) {
      return rejectAndEmpty(manifest.snapshotId());
    }
    var state = mapper.toState(protobuf);
    if (!state.sessionId().equals(manifest.sessionId())
        || state.stateVersion() != manifest.stateVersion()
        || state.targetRevision() != manifest.targetRevision()
        || !state.snapshotKind().equals("FULL_RESYNC")) {
      return rejectAndEmpty(manifest.snapshotId());
    }
    jdbc.update(
        """
        UPDATE browser_state_snapshot_streams
           SET status = 'COMMITTED', committed_at = now(), updated_at = now()
         WHERE snapshot_id = ?
        """,
        manifest.snapshotId());
    jdbc.update(
        "DELETE FROM browser_state_snapshot_chunks WHERE snapshot_id = ?", manifest.snapshotId());
    return Optional.of(state);
  }

  private Manifest requireManifest(NodeEventReceived command, String snapshotId) {
    var manifest =
        findForUpdate(snapshotId)
            .orElseThrow(() -> new IllegalArgumentException("snapshot Begin is not committed"));
    requireMatchingEnvelope(command, manifest);
    return manifest;
  }

  private Optional<Manifest> findForUpdate(String snapshotId) {
    return jdbc.query(
        "SELECT * FROM browser_state_snapshot_streams WHERE snapshot_id = ? FOR UPDATE",
        resultSet -> resultSet.next() ? Optional.of(manifest(resultSet)) : Optional.empty(),
        snapshotId);
  }

  private Manifest manifest(ResultSet resultSet) throws SQLException {
    return new Manifest(
        resultSet.getString("snapshot_id"),
        resultSet.getString("tenant_id"),
        resultSet.getString("session_id"),
        resultSet.getLong("coordinator_term"),
        resultSet.getLong("context_epoch"),
        resultSet.getLong("operation_epoch"),
        resultSet.getLong("state_version"),
        resultSet.getLong("target_revision"),
        resultSet.getInt("total_chunks"),
        resultSet.getLong("total_bytes"),
        resultSet.getString("payload_sha256"),
        resultSet.getString("status"));
  }

  private void requireMatchingEnvelope(NodeEventReceived command, Manifest manifest) {
    if (!manifest.tenantId().equals(command.tenantId())
        || !manifest.sessionId().equals(command.sessionId())
        || manifest.coordinatorTerm() != command.coordinatorTerm()
        || manifest.contextEpoch() != command.contextEpoch()
        || manifest.operationEpoch() != command.operationEpoch()) {
      throw new IllegalArgumentException("snapshot event fence does not match Begin");
    }
  }

  private boolean manifestMatches(
      Manifest manifest, int totalChunks, long totalBytes, String payloadSha256) {
    return manifest.totalChunks() == totalChunks
        && manifest.totalBytes() == totalBytes
        && manifest.payloadSha256().equals(payloadSha256);
  }

  private Optional<NodeEvent.StateUpdated> rejectAndEmpty(String snapshotId) {
    reject(snapshotId);
    return Optional.empty();
  }

  private void reject(String snapshotId) {
    jdbc.update(
        """
        UPDATE browser_state_snapshot_streams
           SET status = 'REJECTED', updated_at = now()
         WHERE snapshot_id = ? AND status IN ('RECEIVING', 'COMMIT_RECEIVED')
        """,
        snapshotId);
    jdbc.update("DELETE FROM browser_state_snapshot_chunks WHERE snapshot_id = ?", snapshotId);
  }

  @Scheduled(fixedDelayString = "${state.snapshot.cleanup-interval-ms:60000}")
  @Transactional
  public void expireStaleStreams() {
    var expired =
        jdbc.queryForList(
            """
            UPDATE browser_state_snapshot_streams
               SET status = 'EXPIRED', updated_at = now()
             WHERE status IN ('RECEIVING', 'COMMIT_RECEIVED') AND expires_at < now()
            RETURNING snapshot_id
            """,
            String.class);
    expired.forEach(
        snapshotId ->
            jdbc.update(
                "DELETE FROM browser_state_snapshot_chunks WHERE snapshot_id = ?", snapshotId));
    jdbc.update(
        "DELETE FROM browser_state_snapshot_streams WHERE status IN ('CANCELLED', 'EXPIRED', 'REJECTED') AND updated_at < ?",
        Instant.now().minus(java.time.Duration.ofDays(1)));
  }

  private String sha256(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record Chunk(int index, String sha256, byte[] payload) {}

  private record Manifest(
      String snapshotId,
      String tenantId,
      String sessionId,
      long coordinatorTerm,
      long contextEpoch,
      long operationEpoch,
      long stateVersion,
      long targetRevision,
      int totalChunks,
      long totalBytes,
      String payloadSha256,
      String status) {
    boolean terminal() {
      return List.of("COMMITTED", "CANCELLED", "EXPIRED", "REJECTED").contains(status);
    }

    Manifest withStatus(String nextStatus) {
      return new Manifest(
          snapshotId,
          tenantId,
          sessionId,
          coordinatorTerm,
          contextEpoch,
          operationEpoch,
          stateVersion,
          targetRevision,
          totalChunks,
          totalBytes,
          payloadSha256,
          nextStatus);
    }
  }
}
