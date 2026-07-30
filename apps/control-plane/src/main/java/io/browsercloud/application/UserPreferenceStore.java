package io.browsercloud.application;

import io.browsercloud.api.UserPreferenceModels.ThemeMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authority for tenant and actor scoped UI preferences. */
@Service
public class UserPreferenceStore {

  private final JdbcTemplate jdbc;

  public UserPreferenceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Optional<StoredUserPreferences> find(String tenantId, String actorId) {
    return jdbc
        .query(
            """
            SELECT theme_mode, updated_at, version
            FROM workspace_user_preferences
            WHERE tenant_id = ? AND actor_id = ?
            """,
            (result, rowNumber) ->
                new StoredUserPreferences(
                    ThemeMode.valueOf(result.getString("theme_mode")),
                    result.getTimestamp("updated_at").toInstant(),
                    result.getLong("version")),
            tenantId,
            actorId)
        .stream()
        .findFirst();
  }

  @Transactional
  public StoredUserPreferences save(
      String tenantId, String actorId, ThemeMode themeMode, Instant now) {
    var values =
        jdbc.query(
            """
            INSERT INTO workspace_user_preferences(
                tenant_id, actor_id, theme_mode, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, 1)
            ON CONFLICT (tenant_id, actor_id)
            DO UPDATE SET
                theme_mode = EXCLUDED.theme_mode,
                updated_at = CASE
                  WHEN workspace_user_preferences.theme_mode IS DISTINCT FROM EXCLUDED.theme_mode
                  THEN EXCLUDED.updated_at
                  ELSE workspace_user_preferences.updated_at
                END,
                version = CASE
                  WHEN workspace_user_preferences.theme_mode IS DISTINCT FROM EXCLUDED.theme_mode
                  THEN workspace_user_preferences.version + 1
                  ELSE workspace_user_preferences.version
                END
            RETURNING theme_mode, updated_at, version
            """,
            (result, rowNumber) ->
                new StoredUserPreferences(
                    ThemeMode.valueOf(result.getString("theme_mode")),
                    result.getTimestamp("updated_at").toInstant(),
                    result.getLong("version")),
            tenantId,
            actorId,
            themeMode.name(),
            Timestamp.from(now),
            Timestamp.from(now));
    if (values.size() != 1) {
      throw new IllegalStateException("User preference write did not return one row");
    }
    return values.getFirst();
  }

  public record StoredUserPreferences(ThemeMode themeMode, Instant updatedAt, long version) {}
}
