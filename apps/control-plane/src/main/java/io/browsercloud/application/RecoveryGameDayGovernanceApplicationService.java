package io.browsercloud.application;

import static io.browsercloud.api.EnterpriseOperationsModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.browsercloud.application.AuditApplicationService.AuditRecord;
import io.browsercloud.application.EnterpriseOperationsApplicationService.EnterpriseResourceNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable Recovery GameDay timeline, signed reporting, trend and remediation governance. */
@Service
public class RecoveryGameDayGovernanceApplicationService {

  private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final AuditApplicationService auditService;
  private final String signingKey;
  private final String signingKeyId;

  public RecoveryGameDayGovernanceApplicationService(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      AuditApplicationService auditService,
      @Value("${enterprise.audit-export.signing-key:local-development-audit-export-key}")
          String signingKey,
      @Value("${enterprise.audit-export.signing-key-id:local-development}") String signingKeyId) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.auditService = auditService;
    this.signingKey = signingKey;
    this.signingKeyId = signingKeyId;
  }

  @Transactional(readOnly = true)
  public RecoveryGameDayEventPage listEvents(String gameDayId, int requestedLimit, String cursor) {
    requireGameDay(gameDayId);
    int limit = Math.max(1, Math.min(requestedLimit, 200));
    var position = decodeCursor(gameDayId, cursor);
    List<RecoveryGameDayEventView> rows;
    if (position == null) {
      rows =
          jdbc.query(
              """
              SELECT * FROM recovery_gameday_job_events
               WHERE gameday_id = ?
               ORDER BY occurred_at DESC, event_id DESC
               LIMIT ?
              """,
              this::event,
              gameDayId,
              limit + 1);
    } else {
      rows =
          jdbc.query(
              """
              SELECT * FROM recovery_gameday_job_events
               WHERE gameday_id = ?
                 AND (occurred_at, event_id) < (?, ?)
               ORDER BY occurred_at DESC, event_id DESC
               LIMIT ?
              """,
              this::event,
              gameDayId,
              java.sql.Timestamp.from(position.occurredAt()),
              position.eventId(),
              limit + 1);
    }
    boolean hasMore = rows.size() > limit;
    var items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    String nextCursor =
        hasMore && !items.isEmpty()
            ? encodeCursor(gameDayId, items.getLast().occurredAt(), items.getLast().eventId())
            : null;
    return new RecoveryGameDayEventPage(items, nextCursor, hasMore);
  }

  @Transactional(readOnly = true)
  public List<RecoveryGameDayTrendView> trends(int requestedWindowDays) {
    int windowDays = Math.max(1, Math.min(requestedWindowDays, 3650));
    return jdbc.query(
        """
        SELECT g.scenario,
               g.environment,
               count(*) AS total_runs,
               count(*) FILTER (WHERE g.state = 'PASSED') AS passed_runs,
               count(*) FILTER (WHERE g.state = 'FAILED') AS failed_runs,
               count(*) FILTER (WHERE g.state = 'ABORTED') AS aborted_runs,
               count(*) FILTER (
                 WHERE g.state IN ('FAILED', 'ABORTED')
                   AND g.recovery_confirmed IS DISTINCT FROM TRUE
               ) AS recovery_unknown_runs,
               round(
                 100.0 * count(*) FILTER (WHERE g.state = 'PASSED') / NULLIF(count(*), 0),
                 2
               ) AS pass_rate_percent,
               percentile_cont(0.95) WITHIN GROUP (ORDER BY g.observed_rto_seconds)
                 FILTER (WHERE g.observed_rto_seconds IS NOT NULL) AS p95_rto_seconds,
               percentile_cont(0.95) WITHIN GROUP (ORDER BY g.observed_rpo_seconds)
                 FILTER (WHERE g.observed_rpo_seconds IS NOT NULL) AS p95_rpo_seconds,
               count(t.ticket_id) FILTER (WHERE t.state <> 'RESOLVED') AS open_ticket_count,
               max(g.started_at) AS latest_run_at
          FROM enterprise_recovery_gamedays g
          LEFT JOIN recovery_gameday_remediation_tickets t ON t.gameday_id = g.gameday_id
         WHERE g.started_at >= now() - (? * interval '1 day')
         GROUP BY g.scenario, g.environment
         ORDER BY max(g.started_at) DESC, g.scenario, g.environment
         LIMIT 200
        """,
        this::trend,
        windowDays);
  }

  @Transactional
  public RecoveryGameDayReportExportView generateReport(
      RecoveryGameDayView gameDay, String actorId) {
    var events = allEvents(gameDay.gameDayId());
    var report = new LinkedHashMap<String, Object>();
    report.put("schemaVersion", "recovery-gameday-report/v1");
    report.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
    report.put(
        "run", objectMapper.convertValue(gameDay, new TypeReference<Map<String, Object>>() {}));
    report.put(
        "timeline",
        objectMapper.convertValue(events, new TypeReference<List<Map<String, Object>>>() {}));
    findRemediation(gameDay.gameDayId())
        .ifPresent(
            remediation ->
                report.put(
                    "remediation",
                    objectMapper.convertValue(
                        remediation, new TypeReference<Map<String, Object>>() {})));
    var id = id("gex_");
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var reportHash = hash(report);
    var signature = hmac(reportHash);
    jdbc.update(
        """
        INSERT INTO recovery_gameday_report_exports(
          export_id, gameday_id, report_format, event_count, report, report_hash,
          signature_algorithm, signing_key_id, signature, generated_by, generated_at
        ) VALUES (?, ?, 'JSON', ?, CAST(? AS jsonb), ?, 'HMAC-SHA256', ?, ?, ?, ?)
        """,
        id,
        gameDay.gameDayId(),
        events.size(),
        json(report),
        reportHash,
        signingKeyId,
        signature,
        actorId,
        java.sql.Timestamp.from(now));
    audit(
        actorId,
        "RECOVERY_GAMEDAY_REPORT",
        id,
        "EXPORT",
        "SIGNED",
        Map.of(
            "gameDayId", gameDay.gameDayId(),
            "eventCount", events.size(),
            "reportHash", reportHash));
    return requireReport(id);
  }

  @Transactional(readOnly = true)
  public RecoveryGameDayReportExportView getReport(String exportId) {
    return requireReport(exportId);
  }

  @Transactional(readOnly = true)
  public List<RecoveryGameDayRemediationView> listRemediations(String state) {
    if (state == null || state.isBlank()) {
      return jdbc.query(
          """
          SELECT * FROM recovery_gameday_remediation_tickets
          ORDER BY CASE severity WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 ELSE 3 END,
                   created_at DESC, ticket_id DESC
          LIMIT 200
          """,
          this::remediation);
    }
    return jdbc.query(
        """
        SELECT * FROM recovery_gameday_remediation_tickets
         WHERE state = ?
         ORDER BY CASE severity WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 ELSE 3 END,
                  created_at DESC, ticket_id DESC
         LIMIT 200
        """,
        this::remediation,
        state);
  }

  @Transactional
  public RecoveryGameDayRemediationView updateRemediation(
      String ticketId, UpdateRecoveryGameDayRemediationRequest request, String actorId) {
    var existing = requireRemediationForUpdate(ticketId);
    if ("RESOLVED".equals(existing.state())) {
      return existing;
    }
    if (("OPEN".equals(existing.state()) && !"ACKNOWLEDGED".equals(request.state()))
        || ("ACKNOWLEDGED".equals(existing.state()) && !"RESOLVED".equals(request.state()))) {
      throw new IllegalArgumentException(
          "remediation must transition OPEN to ACKNOWLEDGED to RESOLVED");
    }
    String ownerId = trimToNull(request.ownerId());
    if (ownerId == null) {
      throw new IllegalArgumentException("remediation owner is required");
    }
    String resolution = trimToNull(request.resolution());
    if ("RESOLVED".equals(request.state()) && resolution == null) {
      throw new IllegalArgumentException("resolved remediation requires a resolution");
    }
    jdbc.update(
        """
        UPDATE recovery_gameday_remediation_tickets
           SET state = ?, owner_id = ?, resolution = ?, updated_by = ?, updated_at = now(),
               resolved_at = CASE WHEN ? = 'RESOLVED' THEN now() ELSE NULL END
         WHERE ticket_id = ?
        """,
        request.state(),
        ownerId,
        resolution,
        actorId,
        request.state(),
        ticketId);
    audit(
        actorId,
        "RECOVERY_GAMEDAY_REMEDIATION",
        ticketId,
        request.state(),
        request.state(),
        Map.of("gameDayId", existing.gameDayId(), "ownerId", ownerId));
    return requireRemediation(ticketId);
  }

  @Transactional
  public RecoveryGameDayRemediationView ensureRemediation(
      String gameDayId, String scenario, String environment, String reasonCode, String actorId) {
    var existing = findRemediation(gameDayId);
    if (existing.isPresent()) {
      return existing.get();
    }
    var ticketId = id("grt_");
    var severity =
        switch (environment) {
          case "PRODUCTION" -> "P1";
          case "STAGING" -> "P2";
          default -> "P3";
        };
    int inserted =
        jdbc.update(
            """
            INSERT INTO recovery_gameday_remediation_tickets(
              ticket_id, gameday_id, scenario, environment, severity, state,
              reason_code, summary, created_by, created_at, updated_by, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, now(), ?, now())
            ON CONFLICT (gameday_id) DO NOTHING
            """,
            ticketId,
            gameDayId,
            scenario,
            environment,
            severity,
            reasonCode,
            "Recovery GameDay requires remediation: " + scenario,
            actorId,
            actorId);
    if (inserted > 0) {
      audit(
          actorId,
          "RECOVERY_GAMEDAY_REMEDIATION",
          ticketId,
          "OPEN",
          severity,
          Map.of("gameDayId", gameDayId, "reasonCode", reasonCode));
    }
    return findRemediation(gameDayId).orElseThrow();
  }

  private List<RecoveryGameDayEventView> allEvents(String gameDayId) {
    return jdbc.query(
        """
        SELECT * FROM recovery_gameday_job_events
         WHERE gameday_id = ?
         ORDER BY occurred_at, event_id
        """,
        this::event,
        gameDayId);
  }

  private RecoveryGameDayEventView event(ResultSet result, int row) throws SQLException {
    return new RecoveryGameDayEventView(
        result.getString("event_id"),
        result.getString("gameday_id"),
        result.getString("event_type"),
        result.getString("from_state"),
        result.getString("to_state"),
        result.getString("stage"),
        result.getString("worker_id"),
        result.getLong("claim_epoch"),
        result.getInt("attempt"),
        result.getString("reason_code"),
        result.getTimestamp("occurred_at").toInstant());
  }

  private RecoveryGameDayTrendView trend(ResultSet result, int row) throws SQLException {
    return new RecoveryGameDayTrendView(
        result.getString("scenario"),
        result.getString("environment"),
        result.getLong("total_runs"),
        result.getLong("passed_runs"),
        result.getLong("failed_runs"),
        result.getLong("aborted_runs"),
        result.getLong("recovery_unknown_runs"),
        Optional.ofNullable(result.getBigDecimal("pass_rate_percent")).orElse(BigDecimal.ZERO),
        roundedNullable(result, "p95_rto_seconds"),
        roundedNullable(result, "p95_rpo_seconds"),
        result.getLong("open_ticket_count"),
        result.getTimestamp("latest_run_at").toInstant());
  }

  private RecoveryGameDayReportExportView report(ResultSet result, int row) throws SQLException {
    return new RecoveryGameDayReportExportView(
        result.getString("export_id"),
        result.getString("gameday_id"),
        result.getString("report_format"),
        result.getInt("event_count"),
        read(result.getString("report"), new TypeReference<Map<String, Object>>() {}),
        result.getString("report_hash"),
        result.getString("signature_algorithm"),
        result.getString("signing_key_id"),
        result.getString("signature"),
        result.getString("generated_by"),
        result.getTimestamp("generated_at").toInstant());
  }

  private RecoveryGameDayRemediationView remediation(ResultSet result, int row)
      throws SQLException {
    return new RecoveryGameDayRemediationView(
        result.getString("ticket_id"),
        result.getString("gameday_id"),
        result.getString("scenario"),
        result.getString("environment"),
        result.getString("severity"),
        result.getString("state"),
        result.getString("reason_code"),
        result.getString("summary"),
        result.getString("owner_id"),
        result.getString("resolution"),
        result.getString("created_by"),
        result.getTimestamp("created_at").toInstant(),
        result.getString("updated_by"),
        result.getTimestamp("updated_at").toInstant(),
        instant(result, "resolved_at"));
  }

  private RecoveryGameDayReportExportView requireReport(String exportId) {
    return jdbc
        .query(
            "SELECT * FROM recovery_gameday_report_exports WHERE export_id = ?",
            this::report,
            exportId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Recovery GameDay Report"));
  }

  private Optional<RecoveryGameDayRemediationView> findRemediation(String gameDayId) {
    return jdbc
        .query(
            "SELECT * FROM recovery_gameday_remediation_tickets WHERE gameday_id = ?",
            this::remediation,
            gameDayId)
        .stream()
        .findFirst();
  }

  private RecoveryGameDayRemediationView requireRemediation(String ticketId) {
    return jdbc
        .query(
            "SELECT * FROM recovery_gameday_remediation_tickets WHERE ticket_id = ?",
            this::remediation,
            ticketId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("GameDay Remediation"));
  }

  private RecoveryGameDayRemediationView requireRemediationForUpdate(String ticketId) {
    return jdbc
        .query(
            """
            SELECT * FROM recovery_gameday_remediation_tickets
             WHERE ticket_id = ? FOR UPDATE
            """,
            this::remediation,
            ticketId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("GameDay Remediation"));
  }

  private void requireGameDay(String gameDayId) {
    long count =
        Optional.ofNullable(
                jdbc.queryForObject(
                    "SELECT count(*) FROM enterprise_recovery_gamedays WHERE gameday_id = ?",
                    Long.class,
                    gameDayId))
            .orElse(0L);
    if (count == 0) {
      throw new EnterpriseResourceNotFoundException("Recovery GameDay");
    }
  }

  private void audit(
      String actorId,
      String eventType,
      String resourceId,
      String action,
      String result,
      Map<String, Object> details) {
    auditService.append(
        new AuditRecord(
            "platform-control",
            null,
            eventType,
            "USER",
            actorId,
            eventType,
            resourceId,
            action,
            result,
            details,
            resourceId));
  }

  static String encodeCursor(String gameDayId, Instant occurredAt, String eventId) {
    return CURSOR_ENCODER.encodeToString(
        (gameDayId + "\n" + occurredAt + "\n" + eventId).getBytes(StandardCharsets.UTF_8));
  }

  static CursorPosition decodeCursor(String gameDayId, String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      var decoded = new String(CURSOR_DECODER.decode(cursor), StandardCharsets.UTF_8);
      var parts = decoded.split("\\n", -1);
      if (parts.length != 3
          || !MessageDigest.isEqual(
              gameDayId.getBytes(StandardCharsets.UTF_8), parts[0].getBytes(StandardCharsets.UTF_8))
          || !parts[2].matches("^gev_[A-Za-z0-9]{20}$")) {
        throw new IllegalArgumentException("invalid GameDay event cursor");
      }
      return new CursorPosition(Instant.parse(parts[1]), parts[2]);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("invalid GameDay event cursor");
    }
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
      throw new IllegalStateException("GameDay report cannot be hashed", exception);
    }
  }

  private String hmac(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of()
          .formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("GameDay report cannot be signed", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("GameDay report cannot be serialized", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("stored GameDay report is invalid", exception);
    }
  }

  private static Integer roundedNullable(ResultSet result, String column) throws SQLException {
    var value = result.getBigDecimal(column);
    return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).intValueExact();
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    var value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  record CursorPosition(Instant occurredAt, String eventId) {}
}
