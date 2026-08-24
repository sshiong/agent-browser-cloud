package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserScreenshotModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authority for state-fenced Agent screenshot requests and their one-time grant. */
@Service
public class AgentBrowserScreenshotStore {
  private final JdbcTemplate jdbc;

  public AgentBrowserScreenshotStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insert(RequestRecord request) {
    return jdbc.update(
            """
            INSERT INTO agent_browser_screenshot_requests(
                screenshot_id, tenant_id, session_id, actor_id, idempotency_key, request_hash,
                request_id, command_id, access_grant_id, planned_evidence_id, node_id,
                coordinator_term, context_epoch, capture_mode, expected_state_version,
                expected_target_revision, expected_state_hash, expected_active_tab_id, element_id,
                requested_region_x, requested_region_y, requested_region_width,
                requested_region_height, state, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'EXECUTING', ?, ?)
            ON CONFLICT (tenant_id, actor_id, idempotency_key) DO NOTHING
            """,
            request.screenshotId(),
            request.tenantId(),
            request.sessionId(),
            request.actorId(),
            request.idempotencyKey(),
            request.requestHash(),
            request.requestId(),
            request.commandId(),
            request.accessGrantId(),
            request.plannedEvidenceId(),
            request.nodeId(),
            request.coordinatorTerm(),
            request.contextEpoch(),
            request.mode().name(),
            request.expectedStateVersion(),
            request.expectedTargetRevision(),
            request.expectedStateHash(),
            request.expectedActiveTabId(),
            request.elementId(),
            number(request.region(), ScreenshotRegion::x),
            number(request.region(), ScreenshotRegion::y),
            number(request.region(), ScreenshotRegion::width),
            number(request.region(), ScreenshotRegion::height),
            Timestamp.from(request.now()),
            Timestamp.from(request.now()))
        == 1;
  }

  @Transactional(readOnly = true)
  public Optional<ScreenshotView> findByIdempotency(
      String tenantId, String actorId, String idempotencyKey) {
    return views(
            """
            WHERE screenshot.tenant_id = ? AND screenshot.actor_id = ?
              AND screenshot.idempotency_key = ?
            """,
            tenantId,
            actorId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<RequestIdentity> findIdentityByIdempotency(
      String tenantId, String actorId, String idempotencyKey) {
    return jdbc
        .query(
            """
            SELECT screenshot_id, session_id, request_hash
            FROM agent_browser_screenshot_requests
            WHERE tenant_id = ? AND actor_id = ? AND idempotency_key = ?
            """,
            (result, rowNumber) ->
                new RequestIdentity(
                    result.getString("screenshot_id"),
                    result.getString("session_id"),
                    result.getString("request_hash")),
            tenantId,
            actorId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<ScreenshotView> find(
      String tenantId, String sessionId, String screenshotId, String actorId) {
    return views(
            """
            WHERE screenshot.tenant_id = ? AND screenshot.session_id = ?
              AND screenshot.screenshot_id = ? AND screenshot.actor_id = ?
            """,
            tenantId,
            sessionId,
            screenshotId,
            actorId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<RedeemClaim> findRedeemClaim(
      String tenantId, String sessionId, String screenshotId, String actorId) {
    return jdbc
        .query(
            """
            SELECT screenshot.access_grant_id
            FROM agent_browser_screenshot_requests screenshot
            JOIN session_evidence_access_grants access_grant
              ON access_grant.grant_id = screenshot.access_grant_id
             AND access_grant.tenant_id = screenshot.tenant_id
             AND access_grant.session_id = screenshot.session_id
             AND access_grant.actor_id = screenshot.actor_id
            WHERE screenshot.tenant_id = ? AND screenshot.session_id = ?
              AND screenshot.screenshot_id = ? AND screenshot.actor_id = ?
              AND screenshot.state = 'COMMITTED'
            """,
            (result, rowNumber) -> new RedeemClaim(result.getString("access_grant_id")),
            tenantId,
            sessionId,
            screenshotId,
            actorId)
        .stream()
        .findFirst();
  }

  @Transactional
  public void complete(Completion completion) {
    var rows =
        jdbc.query(
            """
            SELECT screenshot_id, tenant_id, session_id, actor_id, idempotency_key, request_id,
                   access_grant_id, planned_evidence_id, capture_mode, expected_state_version,
                   expected_target_revision, expected_state_hash, expected_active_tab_id
            FROM agent_browser_screenshot_requests
            WHERE tenant_id = ? AND session_id = ? AND command_id = ?
            FOR UPDATE
            """,
            (result, rowNumber) ->
                new CompletionTarget(
                    result.getString("screenshot_id"),
                    result.getString("tenant_id"),
                    result.getString("session_id"),
                    result.getString("actor_id"),
                    result.getString("idempotency_key"),
                    result.getString("request_id"),
                    result.getString("access_grant_id"),
                    result.getString("planned_evidence_id"),
                    result.getString("capture_mode"),
                    result.getLong("expected_state_version"),
                    result.getLong("expected_target_revision"),
                    result.getString("expected_state_hash"),
                    result.getString("expected_active_tab_id")),
            completion.tenantId(),
            completion.sessionId(),
            completion.commandId());
    if (rows.isEmpty()) return;
    var target = rows.getFirst();
    if (!target.plannedEvidenceId().equals(completion.evidenceId())) {
      throw new AgentBrowserScreenshotStateException("SCREENSHOT_EVIDENCE_ID_MISMATCH");
    }
    if (completion.committed()) {
      if (!target.captureMode().equals(completion.captureMode())
          || target.expectedStateVersion() != completion.capturedStateVersion()
          || target.expectedTargetRevision() != completion.capturedTargetRevision()
          || !target.expectedStateHash().equals(completion.capturedStateHash())
          || !target.expectedActiveTabId().equals(completion.capturedActiveTabId())) {
        throw new AgentBrowserScreenshotStateException("SCREENSHOT_CAPTURE_FENCE_MISMATCH");
      }
      var updated =
          jdbc.update(
              """
              UPDATE agent_browser_screenshot_requests
              SET state = 'COMMITTED', evidence_id = ?, captured_state_version = ?,
                  captured_target_revision = ?, captured_state_hash = ?,
                  captured_active_tab_id = ?, viewport_width = ?, viewport_height = ?,
                  device_scale_factor = ?, captured_region_x = ?, captured_region_y = ?,
                  captured_region_width = ?, captured_region_height = ?, coordinate_space = ?,
                  completed_at = ?, updated_at = ?, version = version + 1
              WHERE screenshot_id = ? AND state = 'EXECUTING'
              """,
              completion.evidenceId(),
              completion.capturedStateVersion(),
              completion.capturedTargetRevision(),
              completion.capturedStateHash(),
              completion.capturedActiveTabId(),
              completion.viewportWidth(),
              completion.viewportHeight(),
              completion.deviceScaleFactor(),
              completion.region().x(),
              completion.region().y(),
              completion.region().width(),
              completion.region().height(),
              completion.coordinateSpace(),
              Timestamp.from(completion.completedAt()),
              Timestamp.from(completion.completedAt()),
              target.screenshotId());
      if (updated == 1) {
        var grantExpiresAt = completion.completedAt().plusSeconds(300);
        if (jdbc.update(
                """
                INSERT INTO session_evidence_access_grants(
                    grant_id, tenant_id, session_id, evidence_id, actor_id, purpose,
                    idempotency_key, request_id, state, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, 'AGENT_PERCEPTION', ?, ?, 'ISSUED', ?, ?)
                ON CONFLICT (grant_id) DO NOTHING
                """,
                target.accessGrantId(),
                target.tenantId(),
                target.sessionId(),
                completion.evidenceId(),
                target.actorId(),
                "agent-screenshot:" + target.screenshotId(),
                target.requestId(),
                Timestamp.from(grantExpiresAt),
                Timestamp.from(completion.completedAt()))
            != 1) {
          throw new AgentBrowserScreenshotStateException("SCREENSHOT_ACCESS_GRANT_CONFLICT");
        }
      }
    } else {
      jdbc.update(
          """
          UPDATE agent_browser_screenshot_requests
          SET state = 'FAILED', error_code = ?, completed_at = ?, updated_at = ?,
              version = version + 1
          WHERE screenshot_id = ? AND state = 'EXECUTING'
          """,
          safeCode(completion.errorCode()),
          Timestamp.from(completion.completedAt()),
          Timestamp.from(completion.completedAt()),
          target.screenshotId());
    }
  }

  @Transactional
  public void failDispatch(String commandId, String errorCode, Instant now) {
    jdbc.update(
        """
        UPDATE agent_browser_screenshot_requests
        SET state = 'FAILED', error_code = ?, completed_at = ?, updated_at = ?, version = version + 1
        WHERE command_id = ? AND state = 'EXECUTING'
        """,
        safeCode(errorCode),
        Timestamp.from(now),
        Timestamp.from(now),
        commandId);
  }

  private List<ScreenshotView> views(String where, Object... arguments) {
    return jdbc.query(
        """
        SELECT screenshot.*,
               evidence.content_sha256, evidence.content_bytes,
               evidence.redaction_state, evidence.redacted_region_count,
               access_grant.expires_at AS grant_expires_at
        FROM agent_browser_screenshot_requests screenshot
        LEFT JOIN session_evidence evidence ON evidence.evidence_id = screenshot.evidence_id
        LEFT JOIN session_evidence_access_grants access_grant
          ON access_grant.grant_id = screenshot.access_grant_id
        """
            + where,
        (result, rowNumber) -> {
          var committed = "COMMITTED".equals(result.getString("state"));
          var expectedCursor =
              cursor(
                  result.getLong("expected_state_version"),
                  result.getLong("expected_target_revision"),
                  result.getString("expected_state_hash"));
          var capturedCursor =
              committed
                  ? cursor(
                      result.getLong("captured_state_version"),
                      result.getLong("captured_target_revision"),
                      result.getString("captured_state_hash"))
                  : null;
          var region =
              committed
                  ? new ScreenshotRegion(
                      result.getDouble("captured_region_x"),
                      result.getDouble("captured_region_y"),
                      result.getDouble("captured_region_width"),
                      result.getDouble("captured_region_height"))
                  : requestedRegion(result.getString("capture_mode"), result);
          return new ScreenshotView(
              result.getString("screenshot_id"),
              result.getString("session_id"),
              ScreenshotMode.valueOf(result.getString("capture_mode")),
              result.getString("state"),
              expectedCursor,
              capturedCursor,
              committed
                  ? result.getString("captured_active_tab_id")
                  : result.getString("expected_active_tab_id"),
              result.getString("element_id"),
              region,
              result.getString("coordinate_space"),
              nullableDouble(result, "viewport_width"),
              nullableDouble(result, "viewport_height"),
              nullableDouble(result, "device_scale_factor"),
              committed ? result.getString("evidence_id") : null,
              committed ? result.getString("access_grant_id") : null,
              instant(result.getTimestamp("grant_expires_at")),
              result.getString("content_sha256"),
              nullableLong(result, "content_bytes"),
              result.getString("redaction_state"),
              nullableInteger(result, "redacted_region_count"),
              result.getString("error_code"),
              result.getString("request_id"),
              result.getTimestamp("created_at").toInstant(),
              result.getTimestamp("updated_at").toInstant(),
              instant(result.getTimestamp("completed_at")));
        },
        arguments);
  }

  private static ScreenshotRegion requestedRegion(String mode, java.sql.ResultSet result)
      throws java.sql.SQLException {
    if (!mode.equals("REGION") && !mode.equals("CHALLENGE_REGION")) return null;
    return new ScreenshotRegion(
        result.getDouble("requested_region_x"),
        result.getDouble("requested_region_y"),
        result.getDouble("requested_region_width"),
        result.getDouble("requested_region_height"));
  }

  private static Double number(
      ScreenshotRegion region, java.util.function.ToDoubleFunction<ScreenshotRegion> field) {
    return region == null ? null : field.applyAsDouble(region);
  }

  private static Double nullableDouble(java.sql.ResultSet result, String column)
      throws java.sql.SQLException {
    var value = result.getDouble(column);
    return result.wasNull() ? null : value;
  }

  private static Long nullableLong(java.sql.ResultSet result, String column)
      throws java.sql.SQLException {
    var value = result.getLong(column);
    return result.wasNull() ? null : value;
  }

  private static Integer nullableInteger(java.sql.ResultSet result, String column)
      throws java.sql.SQLException {
    var value = result.getInt(column);
    return result.wasNull() ? null : value;
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static String cursor(long stateVersion, long targetRevision, String stateHash) {
    return stateVersion + ":" + targetRevision + ":" + stateHash;
  }

  private static String safeCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{2,127}$")
        ? value
        : "SCREENSHOT_CAPTURE_FAILED";
  }

  public record RequestRecord(
      String screenshotId,
      String tenantId,
      String sessionId,
      String actorId,
      String idempotencyKey,
      String requestHash,
      String requestId,
      String commandId,
      String accessGrantId,
      String plannedEvidenceId,
      String nodeId,
      long coordinatorTerm,
      long contextEpoch,
      ScreenshotMode mode,
      long expectedStateVersion,
      long expectedTargetRevision,
      String expectedStateHash,
      String expectedActiveTabId,
      String elementId,
      ScreenshotRegion region,
      Instant now) {}

  public record Completion(
      String tenantId,
      String sessionId,
      String commandId,
      String evidenceId,
      boolean committed,
      String errorCode,
      String captureMode,
      long capturedStateVersion,
      long capturedTargetRevision,
      String capturedStateHash,
      String capturedActiveTabId,
      double viewportWidth,
      double viewportHeight,
      double deviceScaleFactor,
      ScreenshotRegion region,
      String coordinateSpace,
      Instant completedAt) {}

  private record CompletionTarget(
      String screenshotId,
      String tenantId,
      String sessionId,
      String actorId,
      String idempotencyKey,
      String requestId,
      String accessGrantId,
      String plannedEvidenceId,
      String captureMode,
      long expectedStateVersion,
      long expectedTargetRevision,
      String expectedStateHash,
      String expectedActiveTabId) {}

  public record RedeemClaim(String accessGrantId) {}

  public record RequestIdentity(String screenshotId, String sessionId, String requestHash) {}

  public static final class AgentBrowserScreenshotStateException extends RuntimeException {
    public AgentBrowserScreenshotStateException(String message) {
      super(message);
    }
  }
}
