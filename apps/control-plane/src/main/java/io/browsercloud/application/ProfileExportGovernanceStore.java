package io.browsercloud.application;

import static io.browsercloud.api.ProfileExportModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileExportGovernanceStore {

  private final JdbcTemplate jdbc;

  public ProfileExportGovernanceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertGrant(
      String grantId,
      String tenantId,
      String profileId,
      String checkpointId,
      long checkpointEpoch,
      String actorId,
      ProfileExportPurpose purpose,
      String idempotencyKey,
      String requestId,
      Instant expiresAt,
      Instant now) {
    return jdbc.update(
            """
            INSERT INTO profile_export_access_grants(
                grant_id, tenant_id, profile_id, checkpoint_id, checkpoint_epoch,
                actor_id, purpose, idempotency_key, request_id, state, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)
            ON CONFLICT (tenant_id, actor_id, idempotency_key) DO NOTHING
            """,
            grantId,
            tenantId,
            profileId,
            checkpointId,
            checkpointEpoch,
            actorId,
            purpose.name(),
            idempotencyKey,
            requestId,
            Timestamp.from(expiresAt),
            Timestamp.from(now))
        == 1;
  }

  @Transactional(readOnly = true)
  public Optional<ProfileExportGrantView> findByIdempotency(
      String tenantId, String actorId, String idempotencyKey) {
    return views(
            """
            SELECT * FROM profile_export_access_grants
            WHERE tenant_id = ? AND actor_id = ? AND idempotency_key = ?
            """,
            tenantId,
            actorId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Transactional
  public ProfileExportClaim claim(
      String tenantId, String profileId, String grantId, String actorId, Instant now) {
    var rows =
        jdbc.query(
            """
            SELECT export_grant.grant_id, export_grant.profile_id, export_grant.checkpoint_id,
                   export_grant.checkpoint_epoch, export_grant.expires_at
            FROM profile_export_access_grants export_grant
            JOIN profiles profile
              ON profile.profile_id = export_grant.profile_id
             AND profile.tenant_id = export_grant.tenant_id
             AND profile.latest_checkpoint_id = export_grant.checkpoint_id
             AND profile.latest_checkpoint_epoch = export_grant.checkpoint_epoch
            WHERE export_grant.tenant_id = ? AND export_grant.profile_id = ?
              AND export_grant.grant_id = ? AND export_grant.actor_id = ?
              AND export_grant.state = 'ISSUED' AND export_grant.expires_at > ?
            FOR UPDATE OF export_grant, profile
            """,
            (result, rowNumber) ->
                new ProfileExportClaim(
                    result.getString("grant_id"),
                    result.getString("profile_id"),
                    result.getString("checkpoint_id"),
                    result.getLong("checkpoint_epoch"),
                    result.getTimestamp("expires_at").toInstant()),
            tenantId,
            profileId,
            grantId,
            actorId,
            Timestamp.from(now));
    if (rows.isEmpty()
        || jdbc.update(
                """
                UPDATE profile_export_access_grants
                SET state = 'REDEEMING', redeem_started_at = ?
                WHERE grant_id = ? AND state = 'ISSUED'
                """,
                Timestamp.from(now),
                grantId)
            != 1) {
      throw new ProfileExportGovernanceService.ProfileExportRejectedException(
          "PROFILE_EXPORT_GRANT_NOT_REDEEMABLE");
    }
    return rows.getFirst();
  }

  @Transactional
  public void commitGrant(
      String grantId, String nodeId, String archiveSha256, long archiveSizeBytes, Instant now) {
    if (jdbc.update(
            """
            UPDATE profile_export_access_grants
            SET state = 'REDEEMED', redeemed_at = ?, signer_node_id = ?,
                archive_sha256 = ?, archive_size_bytes = ?
            WHERE grant_id = ? AND state = 'REDEEMING'
            """,
            Timestamp.from(now),
            nodeId,
            archiveSha256,
            archiveSizeBytes,
            grantId)
        != 1) {
      throw new ProfileExportGovernanceService.ProfileExportRejectedException(
          "PROFILE_EXPORT_GRANT_STATE_CHANGED");
    }
  }

  @Transactional
  public void failGrant(String grantId, String errorCode, Instant now) {
    jdbc.update(
        """
        UPDATE profile_export_access_grants
        SET state = 'FAILED', redeemed_at = ?, error_code = ?
        WHERE grant_id = ? AND state = 'REDEEMING'
        """,
        Timestamp.from(now),
        safeCode(errorCode),
        grantId);
  }

  private List<ProfileExportGrantView> views(String sql, Object... arguments) {
    return jdbc.query(
        sql,
        (result, rowNumber) ->
            new ProfileExportGrantView(
                result.getString("grant_id"),
                result.getString("profile_id"),
                result.getString("checkpoint_id"),
                result.getLong("checkpoint_epoch"),
                ProfileExportPurpose.valueOf(result.getString("purpose")),
                result.getString("state"),
                result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("created_at").toInstant(),
                instant(result.getTimestamp("redeemed_at")),
                result.getString("archive_sha256"),
                nullableLong(result.getObject("archive_size_bytes")),
                result.getString("error_code"),
                result.getString("request_id")),
        arguments);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static String safeCode(String value) {
    if (value == null || value.isBlank()) return "PROFILE_EXPORT_NODE_FAILED";
    var normalized = value.replaceAll("[^A-Z0-9_.-]", "_");
    return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
  }

  public record ProfileExportClaim(
      String grantId,
      String profileId,
      String checkpointId,
      long checkpointEpoch,
      Instant grantExpiresAt) {}
}
