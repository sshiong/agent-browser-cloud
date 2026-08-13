package io.browsercloud.application;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.persistence.ProfileJpaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authority for committed Profile Warm Tier transaction barriers. */
@Service
public class ProfileWarmTierApplicationService {

  private final JdbcTemplate jdbc;
  private final ProfileJpaRepository profiles;
  private final AuditApplicationService audit;

  public ProfileWarmTierApplicationService(
      JdbcTemplate jdbc, ProfileJpaRepository profiles, AuditApplicationService audit) {
    this.jdbc = jdbc;
    this.profiles = profiles;
    this.audit = audit;
  }

  @Transactional
  public void record(NodeEventReceived command, NodeEvent.ProfileWarmTierSynced event) {
    var profile =
        profiles
            .findById(event.profileId())
            .orElseThrow(() -> new IllegalArgumentException("Warm Tier Profile does not exist"));
    if (!profile.getTenantId().equals(command.tenantId())) {
      throw new TenantAccessDeniedException(event.profileId());
    }
    if (event.profileWriteEpoch() < profile.getProfileWriteEpoch()) {
      return;
    }
    var latest =
        jdbc.query(
            """
            SELECT profile_write_epoch, journal_sequence
              FROM profile_warm_tier_journal_commits
             WHERE tenant_id = ? AND profile_id = ?
             ORDER BY profile_write_epoch DESC, journal_sequence DESC
             LIMIT 1
             FOR UPDATE
            """,
            (result, ignored) -> new long[] {result.getLong(1), result.getLong(2)},
            command.tenantId(),
            event.profileId());
    if (!latest.isEmpty()) {
      var cursor = latest.getFirst();
      if (event.profileWriteEpoch() < cursor[0]
          || (event.profileWriteEpoch() == cursor[0] && event.journalSequence() <= cursor[1])) {
        return;
      }
      // A Browser restart or migration can terminally discard an old-context event after its
      // Region-local manifest was already committed. Each manifest is a complete CAS index, so
      // PostgreSQL only requires a strictly increasing cursor; a missing projected sequence does
      // not make a later committed barrier unusable.
    }
    try {
      jdbc.update(
          """
          INSERT INTO profile_warm_tier_journal_commits (
            event_id, tenant_id, session_id, profile_id, node_id,
            profile_write_epoch, journal_sequence, transaction_barrier,
            changed_file_count, deleted_file_count, reused_chunk_count,
            uploaded_bytes, deferred_group_count, manifest_sha256, committed_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          command.eventId(),
          command.tenantId(),
          command.sessionId(),
          event.profileId(),
          event.nodeId(),
          event.profileWriteEpoch(),
          event.journalSequence(),
          event.transactionBarrier(),
          event.changedFileCount(),
          event.deletedFileCount(),
          event.reusedChunkCount(),
          event.uploadedBytes(),
          event.deferredGroupCount(),
          event.manifestSha256(),
          Timestamp.from(Instant.ofEpochMilli(event.committedAtMs())));
    } catch (DataIntegrityViolationException conflict) {
      throw new WarmTierJournalConflictException("PROFILE_WARM_TIER_LEDGER_CONFLICT", conflict);
    }
    audit.append(
        new AuditApplicationService.AuditRecord(
            command.tenantId(),
            command.sessionId(),
            "PROFILE_WARM_TIER_SYNC",
            "NODE",
            event.nodeId(),
            "PROFILE",
            event.profileId(),
            "PROFILE_WARM_TIER_COMMITTED",
            "COMMITTED",
            Map.of(
                "profileWriteEpoch", event.profileWriteEpoch(),
                "journalSequence", event.journalSequence(),
                "transactionBarrier", event.transactionBarrier(),
                "changedFileCount", event.changedFileCount(),
                "deletedFileCount", event.deletedFileCount(),
                "reusedChunkCount", event.reusedChunkCount(),
                "uploadedBytes", event.uploadedBytes(),
                "deferredGroupCount", event.deferredGroupCount(),
                "manifestSha256", event.manifestSha256()),
            command.eventId()));
  }

  @Transactional(readOnly = true)
  public WarmTierStatus status(String tenantId, String profileId) {
    profiles
        .findById(profileId)
        .filter(profile -> profile.getTenantId().equals(tenantId))
        .orElseThrow(() -> new TenantAccessDeniedException(profileId));
    return jdbc
        .query(
            """
            SELECT node_id, profile_write_epoch, journal_sequence, transaction_barrier,
                   changed_file_count, deleted_file_count, reused_chunk_count,
                   uploaded_bytes, deferred_group_count, manifest_sha256, committed_at
              FROM profile_warm_tier_journal_commits
             WHERE tenant_id = ? AND profile_id = ?
             ORDER BY profile_write_epoch DESC, journal_sequence DESC
             LIMIT 1
            """,
            (result, ignored) ->
                new WarmTierStatus(
                    "LIVE",
                    result.getString(1),
                    result.getLong(2),
                    result.getLong(3),
                    result.getString(4),
                    result.getLong(5),
                    result.getLong(6),
                    result.getLong(7),
                    result.getLong(8),
                    result.getLong(9),
                    result.getString(10),
                    result.getTimestamp(11).toInstant()),
            tenantId,
            profileId)
        .stream()
        .findFirst()
        .orElse(WarmTierStatus.awaiting());
  }

  public record WarmTierStatus(
      String state,
      String nodeId,
      Long profileWriteEpoch,
      Long journalSequence,
      String transactionBarrier,
      Long changedFileCount,
      Long deletedFileCount,
      Long reusedChunkCount,
      Long uploadedBytes,
      Long deferredGroupCount,
      String manifestSha256,
      Instant committedAt) {
    static WarmTierStatus awaiting() {
      return new WarmTierStatus(
          "AWAITING_FIRST_SYNC", null, null, null, null, null, null, null, null, null, null, null);
    }
  }

  public static final class WarmTierJournalConflictException extends RuntimeException {
    public WarmTierJournalConflictException(String code) {
      super(code);
    }

    public WarmTierJournalConflictException(String code, Throwable cause) {
      super(code, cause);
    }
  }
}
