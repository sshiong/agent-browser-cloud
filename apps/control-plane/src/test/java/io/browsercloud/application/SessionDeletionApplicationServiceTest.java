package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.SessionDeletionModels.BatchDeleteSessionsRequest;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.exceptions.InvalidSessionStateException;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class SessionDeletionApplicationServiceTest {

  @Mock private NamedParameterJdbcTemplate jdbc;
  @Mock private OperationRepository operations;
  @Mock private IdempotencyService idempotency;
  @Mock private AuditApplicationService audit;

  private SessionDeletionApplicationService service;

  @BeforeEach
  void setUp() {
    service = new SessionDeletionApplicationService(jdbc, operations, idempotency, audit);
    when(idempotency.claimSessionBatchDelete(anyString(), anyString(), any(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(3));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"HIBERNATED", "TERMINATED"})
  void atomicallySoftDeletesCreatedAndStoppedSessions(String stoppedState) throws Exception {
    stubLockedRows(
        List.of(
            new PersistedSession("ses_1234567890abcdef", "CREATED"),
            new PersistedSession("ses_fedcba0987654321", stoppedState)));
    when(operations.findActiveBySessionIds(any())).thenReturn(Map.of());
    when(jdbc.update(anyString(), anyMap())).thenReturn(2);

    var result =
        service.delete(
            "tenant-test",
            "actor-test",
            new BatchDeleteSessionsRequest(List.of("ses_fedcba0987654321", "ses_1234567890abcdef")),
            "delete-1",
            "request-1");

    assertThat(result.deletedCount()).isEqualTo(2);
    assertThat(result.sessionIds()).containsExactly("ses_1234567890abcdef", "ses_fedcba0987654321");
    assertThat(result.deletionId()).startsWith("sdel_");
    verify(jdbc, atLeastOnce()).update(anyString(), anyMap());
    verify(audit, org.mockito.Mockito.times(2)).append(any());
  }

  @Test
  void rejectsTheWholeBatchWhenOneSessionIsRunning() throws Exception {
    stubLockedRows(
        List.of(
            new PersistedSession("ses_1234567890abcdef", "CREATED"),
            new PersistedSession("ses_fedcba0987654321", "RUNNING")));
    when(operations.findActiveBySessionIds(any())).thenReturn(Map.of());

    assertThatThrownBy(
            () ->
                service.delete(
                    "tenant-test",
                    "actor-test",
                    new BatchDeleteSessionsRequest(
                        List.of("ses_1234567890abcdef", "ses_fedcba0987654321")),
                    "delete-2",
                    "request-2"))
        .isInstanceOf(InvalidSessionStateException.class);

    verify(jdbc, never()).update(anyString(), anyMap());
    verify(audit, never()).append(any());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void stubLockedRows(List<PersistedSession> rows) throws Exception {
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper mapper = invocation.getArgument(2);
              var mapped = new java.util.ArrayList<>();
              for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                var result = mock(ResultSet.class);
                when(result.getString("id")).thenReturn(row.id());
                when(result.getString("state")).thenReturn(row.state());
                mapped.add(mapper.mapRow(result, index));
              }
              return mapped;
            });
  }

  private record PersistedSession(String id, String state) {}
}
