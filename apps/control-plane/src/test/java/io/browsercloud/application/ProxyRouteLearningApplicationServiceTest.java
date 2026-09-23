package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.persistence.AgentTaskEntity;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ProxyRouteLearningApplicationServiceTest {

  @Test
  void recordsOnlyAnAssignmentThatPredatesTheIndependentlyVerifiedTask() {
    var jdbc = mock(JdbcTemplate.class);
    var service =
        new ProxyRouteLearningApplicationService(jdbc, new ObjectMapper().findAndRegisterModules());
    var task = task(Instant.parse("2026-09-23T00:01:00Z"));
    when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any()))
        .thenAnswer(
            invocation -> {
              org.springframework.jdbc.core.RowMapper<?> mapper = invocation.getArgument(1);
              var result = mock(java.sql.ResultSet.class);
              when(result.getString("binding_profile_id")).thenReturn("pbind_1234567890123456");
              when(result.getString("provider_id")).thenReturn("provider-a");
              when(result.getTimestamp("assigned_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-09-23T00:00:00Z")));
              return List.of(mapper.mapRow(result, 0));
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(
            service.recordVerifiedOutcome(
                task,
                "outcome_1234567890123456",
                true,
                List.of("GOAL_SATISFIED"),
                Instant.parse("2026-09-23T00:02:00Z")))
        .isTrue();

    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
    assertThat(sql.getAllValues().getFirst())
        .contains("proxy_route_business_outcomes", "ON CONFLICT DO NOTHING");
    assertThat(sql.getAllValues().get(1)).contains("proxy_route_business_stats", "success_ewma");
  }

  @Test
  void skipsOutcomeWhenTheRouteWasReboundAfterTaskCreation() {
    var jdbc = mock(JdbcTemplate.class);
    var service = new ProxyRouteLearningApplicationService(jdbc, new ObjectMapper());
    var task = task(Instant.parse("2026-09-23T00:01:00Z"));
    when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any()))
        .thenAnswer(
            invocation -> {
              org.springframework.jdbc.core.RowMapper<?> mapper = invocation.getArgument(1);
              var result = mock(java.sql.ResultSet.class);
              when(result.getString("binding_profile_id")).thenReturn("pbind_1234567890123456");
              when(result.getString("provider_id")).thenReturn("provider-a");
              when(result.getTimestamp("assigned_at"))
                  .thenReturn(Timestamp.from(Instant.parse("2026-09-23T00:02:00Z")));
              return List.of(mapper.mapRow(result, 0));
            });

    assertThat(
            service.recordVerifiedOutcome(
                task,
                "outcome_1234567890123456",
                false,
                List.of("GOAL_NOT_SATISFIED"),
                Instant.parse("2026-09-23T00:03:00Z")))
        .isFalse();
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void keepsSparseEvidenceNeutralAndBlendsBayesianAndRecentSuccessAfterThreshold() {
    assertThat(
            ProxyRouteLearningApplicationService.businessScore(4, 4, new BigDecimal("1.0000000")))
        .isEqualTo(50.0);
    assertThat(
            ProxyRouteLearningApplicationService.businessScore(10, 8, new BigDecimal("0.7500000")))
        .isEqualTo(72.857);
  }

  private static AgentTaskEntity task(Instant createdAt) {
    var task = mock(AgentTaskEntity.class);
    when(task.getTenantId()).thenReturn("tenant-test");
    when(task.getSessionId()).thenReturn("ses_test");
    when(task.getTaskId()).thenReturn("task_test");
    when(task.getCreatedAt()).thenReturn(createdAt);
    return task;
  }
}
