package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceOverviewModels.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds an authoritative, bounded Workspace Overview in one PostgreSQL statement. */
@Service
public class WorkspaceOverviewApplicationService {
  private final JdbcTemplate jdbc;

  public WorkspaceOverviewApplicationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public WorkspaceOverviewResponse get(String tenantId, boolean includePlatformCapacity) {
    var snapshot =
        jdbc.queryForObject(
            """
            WITH session_stats AS (
              SELECT
                count(*) AS session_total,
                count(*) FILTER (WHERE state = 'RUNNING') AS session_running,
                count(*) FILTER (
                  WHERE state IN ('CREATED', 'STARTING', 'RECOVERING')
                ) AS session_pending,
                count(*) FILTER (
                  WHERE state IN ('DEGRADED', 'FAILED')
                ) AS session_unhealthy,
                count(*) FILTER (WHERE state = 'HIBERNATED') AS session_hibernated,
                count(*) FILTER (WHERE state = 'TERMINATED') AS session_terminated
              FROM sessions WHERE tenant_id = ?
            ), operation_stats AS (
              SELECT count(*) AS active_operations
              FROM exclusive_operations operation
              JOIN sessions session ON session.id = operation.session_id
              WHERE session.tenant_id = ? AND operation.state = 'ACTIVE'
            ), node_stats AS (
              SELECT
                count(*) AS node_total,
                count(*) FILTER (
                  WHERE lifecycle_state = 'READY'
                    AND admission_state = 'OPEN'
                    AND pressure_state = 'NORMAL'
                    AND last_heartbeat_at >= now() - interval '30 seconds'
                ) AS node_ready,
                count(*) FILTER (
                  WHERE lifecycle_state <> 'READY'
                     OR admission_state <> 'OPEN'
                     OR pressure_state <> 'NORMAL'
                     OR last_heartbeat_at < now() - interval '30 seconds'
                ) AS node_constrained,
                COALESCE(sum(active_sessions), 0) AS node_active_sessions,
                COALESCE(sum(max_sessions), 0) AS node_maximum_sessions,
                COALESCE(sum(reserved_cpu_millis), 0) AS node_reserved_cpu_millis,
                COALESCE(sum(certified_cpu_millis), 0) AS node_certified_cpu_millis,
                COALESCE(sum(reserved_memory_mib), 0) AS node_reserved_memory_mib,
                COALESCE(sum(certified_memory_mib), 0) AS node_certified_memory_mib
              FROM browser_nodes
              WHERE ?
            ), proxy_stats AS (
              SELECT
                count(*) FILTER (WHERE state <> 'RELEASED') AS active_allocations,
                count(DISTINCT session_id) FILTER (
                  WHERE state <> 'RELEASED' AND session_id IS NOT NULL
                ) AS bound_sessions
              FROM proxy_allocations WHERE tenant_id = ?
            ), agent_stats AS (
              SELECT
                count(*) FILTER (
                  WHERE state IN (
                    'PLANNED', 'QUEUED', 'AWAITING_REVIEW', 'RUNNING', 'AWAITING_CONFIRMATION',
                    'WAITING_FOR_HUMAN', 'PAUSED_BY_RESOURCE_POLICY'
                  )
                ) AS agent_active,
                count(*) FILTER (
                  WHERE state IN ('AWAITING_CONFIRMATION', 'WAITING_FOR_HUMAN')
                ) AS agent_awaiting_human,
                count(*) FILTER (
                  WHERE state = 'PAUSED_BY_RESOURCE_POLICY'
                ) AS agent_resource_paused,
                count(*) FILTER (
                  WHERE state = 'FAILED' AND updated_at >= now() - interval '24 hours'
                ) AS agent_failed_last_24_hours
              FROM agent_tasks WHERE tenant_id = ?
            ), cost_stats AS (
              SELECT
                COALESCE(sum(policy.current_hourly_cost), 0) AS current_hourly_cost,
                count(*) FILTER (WHERE policy.current_hourly_cost IS NULL) AS missing_price
              FROM session_resource_policies policy
              JOIN sessions session ON session.id = policy.session_id
              WHERE policy.tenant_id = ? AND session.state <> 'TERMINATED'
            ), security_stats AS (
              SELECT
                count(*) FILTER (WHERE severity = 'WARNING') AS security_warning_last_24_hours,
                count(*) FILTER (WHERE severity = 'CRITICAL') AS security_critical_last_24_hours
              FROM workspace_notifications
              WHERE tenant_id = ?
                AND category = 'SECURITY'
                AND created_at >= now() - interval '24 hours'
            ), overview_cursor AS (
              SELECT COALESCE(max(stream_sequence), 0) AS overview_cursor
              FROM workspace_overview_events
              WHERE tenant_id = ? OR (? AND tenant_id IS NULL)
            )
            SELECT *
            FROM session_stats, operation_stats, node_stats, proxy_stats,
                 agent_stats, cost_stats, security_stats, overview_cursor
            """,
            WorkspaceOverviewApplicationService::mapSnapshot,
            tenantId,
            tenantId,
            includePlatformCapacity,
            tenantId,
            tenantId,
            tenantId,
            tenantId,
            tenantId,
            includePlatformCapacity);
    if (snapshot == null)
      throw new IllegalStateException("Workspace Overview query returned no row");
    return new WorkspaceOverviewResponse(
        snapshot.sessions(),
        snapshot.operations(),
        new BrowserNodeSummary(
            includePlatformCapacity,
            snapshot.browserNodes().total(),
            snapshot.browserNodes().ready(),
            snapshot.browserNodes().constrained(),
            snapshot.browserNodes().activeSessions(),
            snapshot.browserNodes().maximumSessions(),
            snapshot.browserNodes().reservedCpuMillis(),
            snapshot.browserNodes().certifiedCpuMillis(),
            snapshot.browserNodes().reservedMemoryMib(),
            snapshot.browserNodes().certifiedMemoryMib()),
        snapshot.proxies(),
        snapshot.agents(),
        snapshot.cost(),
        snapshot.security(),
        snapshot.cursor(),
        Instant.now());
  }

  private static Snapshot mapSnapshot(ResultSet result, int row) throws SQLException {
    return new Snapshot(
        new SessionSummary(
            result.getLong("session_total"),
            result.getLong("session_running"),
            result.getLong("session_pending"),
            result.getLong("session_unhealthy"),
            result.getLong("session_hibernated"),
            result.getLong("session_terminated")),
        new OperationSummary(result.getLong("active_operations")),
        new BrowserNodeSummary(
            true,
            result.getLong("node_total"),
            result.getLong("node_ready"),
            result.getLong("node_constrained"),
            result.getLong("node_active_sessions"),
            result.getLong("node_maximum_sessions"),
            result.getLong("node_reserved_cpu_millis"),
            result.getLong("node_certified_cpu_millis"),
            result.getLong("node_reserved_memory_mib"),
            result.getLong("node_certified_memory_mib")),
        new ProxySummary(result.getLong("active_allocations"), result.getLong("bound_sessions")),
        new AgentSummary(
            result.getLong("agent_active"),
            result.getLong("agent_awaiting_human"),
            result.getLong("agent_resource_paused"),
            result.getLong("agent_failed_last_24_hours")),
        new CostSummary(
            valueOrZero(result.getBigDecimal("current_hourly_cost")),
            result.getLong("missing_price")),
        new SecuritySummary(
            result.getLong("security_warning_last_24_hours"),
            result.getLong("security_critical_last_24_hours")),
        result.getLong("overview_cursor"));
  }

  private static BigDecimal valueOrZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private record Snapshot(
      SessionSummary sessions,
      OperationSummary operations,
      BrowserNodeSummary browserNodes,
      ProxySummary proxies,
      AgentSummary agents,
      CostSummary cost,
      SecuritySummary security,
      long cursor) {}
}
