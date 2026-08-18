package io.browsercloud.application;

import static io.browsercloud.api.AgentInputSecretModels.*;

import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.agent.AgentModels.ActionDataClass;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores encrypted, one-time credential/OTP inputs and materializes them only into a sealed Step.
 */
@Service
public class AgentInputSecretApplicationService {

  private static final Duration MAX_TTL = Duration.ofMinutes(30);
  private final JdbcTemplate jdbc;
  private final AgentActionPayloadService payloads;
  private final AgentControlPolicyService policies;
  private final AuditApplicationService audit;

  public AgentInputSecretApplicationService(
      JdbcTemplate jdbc,
      AgentActionPayloadService payloads,
      AgentControlPolicyService policies,
      AuditApplicationService audit) {
    this.jdbc = jdbc;
    this.payloads = payloads;
    this.policies = policies;
    this.audit = audit;
  }

  @Transactional
  public AgentInputSecretView create(
      String sessionId,
      String tenantId,
      String actorId,
      CreateAgentInputSecretRequest request,
      String idempotencyKey,
      String requestId) {
    var policy = policies.require(sessionId, tenantId);
    if (!policy.autonomous()) {
      throw new AgentInputSecretRejectedException("AGENT_AUTONOMOUS_MODE_REQUIRED");
    }
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var defaultTtl =
        request.purpose() == AgentInputSecretPurpose.OTP
            ? Duration.ofMinutes(3)
            : Duration.ofMinutes(15);
    var expiresAt = request.expiresAt() == null ? now.plus(defaultTtl) : request.expiresAt();
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_TTL))) {
      throw new IllegalArgumentException("Agent input secret expiry must be within 30 minutes");
    }
    var fingerprint =
        payloads.fingerprintReference(
            String.join(
                "\n",
                tenantId,
                sessionId,
                request.purpose().name(),
                request.value(),
                request.expiresAt() == null ? "DEFAULT_EXPIRY" : request.expiresAt().toString()));
    var existing = findByIdempotencyKey(tenantId, sessionId, idempotencyKey);
    if (existing != null) {
      if (!existing.fingerprint().equals(fingerprint)) {
        throw new AgentInputSecretRejectedException("AGENT_INPUT_SECRET_IDEMPOTENCY_CONFLICT");
      }
      return existing.view();
    }
    var secretId = "ais_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    var sealed = payloads.sealReference(tenantId, sessionId, secretId, request.value());
    var inserted =
        jdbc.update(
            """
        INSERT INTO agent_input_secrets(
          secret_id, tenant_id, session_id, purpose, sealed_value, value_length,
          idempotency_key, request_fingerprint, created_by, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, session_id, idempotency_key) DO NOTHING
        """,
            secretId,
            tenantId,
            sessionId,
            request.purpose().name(),
            sealed,
            request.value().length(),
            idempotencyKey,
            fingerprint,
            actorId,
            Timestamp.from(now),
            Timestamp.from(expiresAt));
    if (inserted == 0) {
      var concurrent = findByIdempotencyKey(tenantId, sessionId, idempotencyKey);
      if (concurrent == null || !concurrent.fingerprint().equals(fingerprint)) {
        throw new AgentInputSecretRejectedException("AGENT_INPUT_SECRET_IDEMPOTENCY_CONFLICT");
      }
      return concurrent.view();
    }
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "AGENT_INPUT_SECRET_CREATED",
            "USER",
            actorId,
            "AGENT_INPUT_SECRET",
            secretId,
            "CREATE",
            "COMMITTED",
            Map.of("purpose", request.purpose().name(), "expiresAt", expiresAt.toString()),
            requestId));
    return new AgentInputSecretView(secretId, sessionId, request.purpose(), expiresAt, false);
  }

  private ExistingSecret findByIdempotencyKey(
      String tenantId, String sessionId, String idempotencyKey) {
    return jdbc
        .query(
            """
            SELECT secret_id, purpose, expires_at, consumed_at, request_fingerprint
            FROM agent_input_secrets
            WHERE tenant_id = ? AND session_id = ? AND idempotency_key = ?
            """,
            (result, ignored) ->
                new ExistingSecret(
                    result.getString("request_fingerprint"),
                    new AgentInputSecretView(
                        result.getString("secret_id"),
                        sessionId,
                        AgentInputSecretPurpose.valueOf(result.getString("purpose")),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("consumed_at") != null)),
            tenantId,
            sessionId,
            idempotencyKey)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Scheduled(fixedDelayString = "${agent.input-secret-cleanup-ms:60000}")
  public void purgeExpiredCiphertexts() {
    jdbc.update(
        """
        DELETE FROM agent_input_secrets
        WHERE expires_at < now() - interval '1 hour'
           OR consumed_at < now() - interval '1 hour'
        """);
  }

  /** Must execute inside the task-create transaction; rollback restores one-time availability. */
  public ResolvedSecret consume(
      String secretId,
      String sessionId,
      String tenantId,
      String taskId,
      ActionDataClass expectedDataClass) {
    if (!policies.require(sessionId, tenantId).autonomous()) {
      throw new AgentInputSecretRejectedException("AGENT_AUTONOMOUS_MODE_REQUIRED");
    }
    var row =
        jdbc
            .query(
                """
                SELECT purpose, sealed_value, value_length, expires_at, consumed_at
                FROM agent_input_secrets
                WHERE secret_id = ? AND session_id = ? AND tenant_id = ?
                FOR UPDATE
                """,
                (result, ignored) ->
                    new SecretRow(
                        AgentInputSecretPurpose.valueOf(result.getString("purpose")),
                        result.getString("sealed_value"),
                        result.getInt("value_length"),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("consumed_at") == null
                            ? null
                            : result.getTimestamp("consumed_at").toInstant()),
                secretId,
                sessionId,
                tenantId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new TenantAccessDeniedException(secretId));
    if (row.consumedAt() != null) {
      throw new AgentInputSecretRejectedException("AGENT_INPUT_SECRET_CONSUMED");
    }
    if (!row.expiresAt().isAfter(Instant.now())) {
      throw new AgentInputSecretRejectedException("AGENT_INPUT_SECRET_EXPIRED");
    }
    var purposeMatches =
        expectedDataClass == ActionDataClass.OTP
            ? row.purpose() == AgentInputSecretPurpose.OTP
            : expectedDataClass == ActionDataClass.CREDENTIAL
                && row.purpose() != AgentInputSecretPurpose.OTP;
    if (!purposeMatches) {
      throw new AgentInputSecretRejectedException("AGENT_INPUT_SECRET_PURPOSE_MISMATCH");
    }
    if (jdbc.update(
            """
            UPDATE agent_input_secrets SET consumed_at = now(), consumed_by_task = ?
            WHERE secret_id = ? AND consumed_at IS NULL
            """,
            taskId,
            secretId)
        != 1) {
      throw new AgentInputSecretRejectedException("AGENT_INPUT_SECRET_CONSUMED");
    }
    var plaintext = payloads.unsealReference(tenantId, sessionId, secretId, row.sealedValue());
    return new ResolvedSecret(plaintext, row.valueLength(), row.purpose());
  }

  public record ResolvedSecret(
      String plaintext, int valueLength, AgentInputSecretPurpose purpose) {}

  private record SecretRow(
      AgentInputSecretPurpose purpose,
      String sealedValue,
      int valueLength,
      Instant expiresAt,
      Instant consumedAt) {}

  private record ExistingSecret(String fingerprint, AgentInputSecretView view) {}

  public static final class AgentInputSecretRejectedException extends RuntimeException {
    public AgentInputSecretRejectedException(String reason) {
      super(reason);
    }
  }
}
