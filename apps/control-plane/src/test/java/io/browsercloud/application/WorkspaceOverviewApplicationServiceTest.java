package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class WorkspaceOverviewApplicationServiceTest {
  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  void mapsTheAuthoritativeWorkspaceSnapshotWithoutBrowserSideCounting() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var result = mock(ResultSet.class);
    stubLongs(
        result,
        "session_total",
        31,
        "session_running",
        12,
        "session_pending",
        3,
        "session_unhealthy",
        2,
        "session_hibernated",
        4,
        "session_terminated",
        10,
        "active_operations",
        5,
        "node_total",
        4,
        "node_ready",
        3,
        "node_constrained",
        1,
        "node_active_sessions",
        18,
        "node_maximum_sessions",
        40,
        "node_reserved_cpu_millis",
        8000,
        "node_certified_cpu_millis",
        16000,
        "node_reserved_memory_mib",
        12000,
        "node_certified_memory_mib",
        32000,
        "active_allocations",
        11,
        "bound_sessions",
        9,
        "agent_active",
        7,
        "agent_awaiting_human",
        2,
        "agent_resource_paused",
        1,
        "agent_failed_last_24_hours",
        3,
        "missing_price",
        2,
        "security_warning_last_24_hours",
        5,
        "security_critical_last_24_hours",
        1,
        "overview_cursor",
        88);
    when(result.getBigDecimal("current_hourly_cost")).thenReturn(new BigDecimal("1.250000"));
    doAnswer(
            invocation -> {
              RowMapper mapper = invocation.getArgument(1);
              return mapper.mapRow(result, 0);
            })
        .when(jdbc)
        .queryForObject(anyString(), any(RowMapper.class), any(Object[].class));

    var overview = new WorkspaceOverviewApplicationService(jdbc).get("tenant-a", true);

    assertThat(overview.sessions().total()).isEqualTo(31);
    assertThat(overview.sessions().running()).isEqualTo(12);
    assertThat(overview.operations().active()).isEqualTo(5);
    assertThat(overview.browserNodes().ready()).isEqualTo(3);
    assertThat(overview.browserNodes().visible()).isTrue();
    assertThat(overview.proxies().boundSessions()).isEqualTo(9);
    assertThat(overview.agents().pausedByResourcePolicy()).isEqualTo(1);
    assertThat(overview.cost().currentHourlyUsd()).isEqualByComparingTo("1.250000");
    assertThat(overview.security().criticalLast24Hours()).isEqualTo(1);
    assertThat(overview.cursor()).isEqualTo(88);
  }

  private static void stubLongs(ResultSet result, Object... labelsAndValues) throws Exception {
    for (var index = 0; index < labelsAndValues.length; index += 2) {
      when(result.getLong((String) labelsAndValues[index]))
          .thenReturn(((Number) labelsAndValues[index + 1]).longValue());
    }
  }
}
