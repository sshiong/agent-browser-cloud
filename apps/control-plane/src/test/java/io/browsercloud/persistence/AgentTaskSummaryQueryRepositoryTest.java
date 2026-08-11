package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.AgentTaskSummaryView;
import io.browsercloud.domain.agent.AgentPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@SuppressWarnings("unchecked")
class AgentTaskSummaryQueryRepositoryTest {

  @Test
  void roundTripsStableNanosecondCursor() {
    var createdAt = Instant.parse("2026-08-01T08:10:12.123456Z");
    var encoded = AgentTaskSummaryQueryRepository.encodeCursor(createdAt, "agt_1234567890abcdef");

    assertThat(AgentTaskSummaryQueryRepository.decodeCursor(encoded))
        .isEqualTo(
            new AgentTaskSummaryQueryRepository.CursorPosition(createdAt, "agt_1234567890abcdef"));
  }

  @Test
  void rejectsMalformedCursorBeforeQueryingPostgres() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    var repository = new AgentTaskSummaryQueryRepository(jdbc);

    assertThatThrownBy(() -> repository.list("tenant-test", 20, "not-a-cursor"))
        .isInstanceOf(AgentTaskSummaryQueryRepository.InvalidAgentTaskSummaryCursorException.class);
    verify(jdbc, times(0))
        .query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
  }

  @Test
  void returnsBoundedProjectionAndMetricsWithTwoFixedQueries() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    var first = summary("agt_1111111111111111", "2026-08-01T08:10:12Z");
    var second = summary("agt_2222222222222222", "2026-08-01T08:10:11Z");
    var lookahead = summary("agt_3333333333333333", "2026-08-01T08:10:10Z");
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(first, second, lookahead));
    when(jdbc.queryForMap(anyString(), any(Map.class)))
        .thenReturn(
            Map.of(
                "total", 9L,
                "planned", 4L,
                "completed", 3L,
                "blocked", 2L));

    var response = new AgentTaskSummaryQueryRepository(jdbc).list("tenant-test", 2, null);

    assertThat(response.items()).containsExactly(first, second);
    assertThat(response.total()).isEqualTo(9);
    assertThat(response.metrics().blocked()).isEqualTo(2);
    assertThat(response.hasMore()).isTrue();
    assertThat(response.nextCursor()).isNotBlank();

    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
    verify(jdbc).queryForMap(anyString(), any(Map.class));
    assertThat(sql.getValue())
        .contains("ORDER BY task.created_at DESC, task.task_id DESC")
        .doesNotContain("task.allowed_domains")
        .doesNotContain("task.execution_results")
        .doesNotContain("task.security_events,");
  }

  private static AgentTaskSummaryView summary(String taskId, String createdAt) {
    var instant = Instant.parse(createdAt);
    return new AgentTaskSummaryView(
        taskId,
        "ses_1234567890abcdef",
        "Summarize current page",
        "PLANNED",
        "R0_READ_ONLY",
        "ALLOWED",
        null,
        AgentPolicy.BALANCED,
        0,
        3,
        0,
        null,
        null,
        instant,
        instant);
  }
}
