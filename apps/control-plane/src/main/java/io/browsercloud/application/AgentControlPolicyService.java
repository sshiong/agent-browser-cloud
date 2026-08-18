package io.browsercloud.application;

import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.agent.AgentModels.AgentControlMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Authoritative tenant/session policy for sensitive Agent actions. */
@Service
public final class AgentControlPolicyService {

  private final JdbcTemplate jdbc;

  public AgentControlPolicyService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Policy require(String sessionId, String tenantId) {
    return jdbc
        .query(
            """
            SELECT agent_control_mode, agent_sensitive_input_max_attempts
            FROM sessions WHERE id = ? AND tenant_id = ?
            """,
            (result, row) ->
                new Policy(
                    AgentControlMode.valueOf(result.getString("agent_control_mode")),
                    result.getInt("agent_sensitive_input_max_attempts")),
            sessionId,
            tenantId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new TenantAccessDeniedException(sessionId));
  }

  public record Policy(AgentControlMode mode, int sensitiveInputMaximumAttempts) {
    public boolean autonomous() {
      return mode == AgentControlMode.AUTONOMOUS;
    }
  }
}
