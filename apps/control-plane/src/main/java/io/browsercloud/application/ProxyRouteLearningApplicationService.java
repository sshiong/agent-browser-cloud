package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.persistence.AgentTaskEntity;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal, tenant-scoped feedback loop for Proxy routing.
 *
 * <p>Only the independent Outcome Verifier may append observations. The route assignment must
 * predate the task so a mid-task rebind cannot be credited or blamed. Goals, URLs, page content and
 * model output are never stored.
 */
@Service
public class ProxyRouteLearningApplicationService {

  static final long MINIMUM_LEARNING_SAMPLES = 5;
  static final long EXPLORATION_TARGET_SAMPLES = 20;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public ProxyRouteLearningApplicationService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public boolean recordVerifiedOutcome(
      AgentTaskEntity task,
      String verificationId,
      boolean verified,
      List<String> reasonCodes,
      Instant observedAt) {
    var assignments =
        jdbc.query(
            """
            SELECT binding_profile_id, provider_id, assigned_at
              FROM session_proxy_binding_assignments
             WHERE tenant_id = ? AND session_id = ?
            """,
            (result, row) ->
                new Assignment(
                    result.getString("binding_profile_id"),
                    result.getString("provider_id"),
                    result.getTimestamp("assigned_at").toInstant()),
            task.getTenantId(),
            task.getSessionId());
    if (assignments.size() != 1
        || assignments.getFirst().assignedAt().isAfter(task.getCreatedAt())) {
      return false;
    }
    var assignment = assignments.getFirst();
    var inserted =
        jdbc.update(
            """
            INSERT INTO proxy_route_business_outcomes(
              verification_id, tenant_id, session_id, task_id, binding_profile_id, provider_id,
              decision, reason_codes, observed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT DO NOTHING
            """,
            verificationId,
            task.getTenantId(),
            task.getSessionId(),
            task.getTaskId(),
            assignment.bindingProfileId(),
            assignment.providerId(),
            verified ? "VERIFIED" : "NOT_VERIFIED",
            writeReasons(reasonCodes),
            Timestamp.from(observedAt));
    if (inserted == 0) {
      return false;
    }
    var sample = verified ? BigDecimal.ONE : BigDecimal.ZERO;
    jdbc.update(
        """
        INSERT INTO proxy_route_business_stats(
          binding_profile_id, tenant_id, provider_id, sample_count, verified_count,
          rejected_count, success_ewma, consecutive_failures, last_observed_at
        ) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
        ON CONFLICT (binding_profile_id) DO UPDATE SET
          provider_id = EXCLUDED.provider_id,
          sample_count = CASE
            WHEN proxy_route_business_stats.provider_id = EXCLUDED.provider_id
              THEN proxy_route_business_stats.sample_count + 1 ELSE 1 END,
          verified_count = CASE
            WHEN proxy_route_business_stats.provider_id = EXCLUDED.provider_id
              THEN proxy_route_business_stats.verified_count + EXCLUDED.verified_count
              ELSE EXCLUDED.verified_count END,
          rejected_count = CASE
            WHEN proxy_route_business_stats.provider_id = EXCLUDED.provider_id
              THEN proxy_route_business_stats.rejected_count + EXCLUDED.rejected_count
              ELSE EXCLUDED.rejected_count END,
          success_ewma = CASE
            WHEN proxy_route_business_stats.provider_id = EXCLUDED.provider_id THEN ROUND(
              proxy_route_business_stats.success_ewma * 0.9000000
                + EXCLUDED.success_ewma * 0.1000000,
              7
            ) ELSE EXCLUDED.success_ewma END,
          consecutive_failures = CASE
            WHEN EXCLUDED.verified_count = 1 THEN 0
            WHEN proxy_route_business_stats.provider_id = EXCLUDED.provider_id
              THEN proxy_route_business_stats.consecutive_failures + 1
            ELSE 1
          END,
          last_observed_at = EXCLUDED.last_observed_at
        WHERE proxy_route_business_stats.tenant_id = EXCLUDED.tenant_id
        """,
        assignment.bindingProfileId(),
        task.getTenantId(),
        assignment.providerId(),
        verified ? 1 : 0,
        verified ? 0 : 1,
        sample,
        verified ? 0 : 1,
        Timestamp.from(observedAt));
    return true;
  }

  @Transactional(readOnly = true)
  public Map<String, BusinessOutcomeEvidence> evidence(String tenantId) {
    var result = new LinkedHashMap<String, BusinessOutcomeEvidence>();
    jdbc.query(
        """
        SELECT binding_profile_id, provider_id, sample_count, verified_count, rejected_count,
               success_ewma, consecutive_failures, last_observed_at
          FROM proxy_route_business_stats
         WHERE tenant_id = ?
         ORDER BY binding_profile_id
        """,
        row -> {
          var samples = row.getLong("sample_count");
          var verified = row.getLong("verified_count");
          var ewma = row.getBigDecimal("success_ewma");
          var score = businessScore(samples, verified, ewma);
          result.put(
              row.getString("binding_profile_id"),
              new BusinessOutcomeEvidence(
                  row.getString("provider_id"),
                  samples,
                  verified,
                  row.getLong("rejected_count"),
                  ewma.doubleValue(),
                  score,
                  row.getInt("consecutive_failures"),
                  row.getTimestamp("last_observed_at").toInstant()));
        },
        tenantId);
    return Map.copyOf(result);
  }

  @Transactional(readOnly = true)
  public String previousBindingForProfile(
      String tenantId, String profileId, String currentSessionId) {
    if (profileId == null || profileId.isBlank()) {
      return null;
    }
    var matches =
        jdbc.queryForList(
            """
            SELECT assignment.binding_profile_id
              FROM session_proxy_binding_assignments assignment
              JOIN sessions session
                ON session.id = assignment.session_id
               AND session.tenant_id = assignment.tenant_id
             WHERE assignment.tenant_id = ?
               AND session.profile_id = ?
               AND session.id <> ?
               AND session.deleted_at IS NULL
             ORDER BY assignment.assigned_at DESC, assignment.session_id DESC
             LIMIT 1
            """,
            String.class,
            tenantId,
            profileId,
            currentSessionId);
    return matches.isEmpty() ? null : matches.getFirst();
  }

  public static BusinessOutcomeEvidence neutralEvidence() {
    return new BusinessOutcomeEvidence(null, 0, 0, 0, 0.5, 50.0, 0, null);
  }

  static double businessScore(long samples, long verified, BigDecimal ewma) {
    if (samples < MINIMUM_LEARNING_SAMPLES) {
      return 50.0;
    }
    var bayesian = 100.0 * (verified + 2.0) / (samples + 4.0);
    return Math.round((bayesian * 0.6 + ewma.doubleValue() * 100.0 * 0.4) * 1000.0) / 1000.0;
  }

  private String writeReasons(Collection<String> reasonCodes) {
    try {
      return objectMapper.writeValueAsString(reasonCodes);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot serialize Proxy route outcome reasons", error);
    }
  }

  private record Assignment(String bindingProfileId, String providerId, Instant assignedAt) {}

  public record BusinessOutcomeEvidence(
      String providerId,
      long sampleCount,
      long verifiedCount,
      long rejectedCount,
      double successEwma,
      double score,
      int consecutiveFailures,
      Instant lastObservedAt) {}
}
