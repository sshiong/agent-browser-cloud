package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class RemoteDesktopParticipantHistoryQueryRepositoryTest {
  @Test
  void bindsCursorToExactSessionAndRoundTripsPosition() {
    var observedAt = Instant.parse("2026-08-12T00:00:00.123456789Z");
    var cursor =
        RemoteDesktopParticipantHistoryQueryRepository.encodeCursor(
            "ses_1234567890abcdef", observedAt, "rdc_1234567890abcdefghij");

    var decoded =
        RemoteDesktopParticipantHistoryQueryRepository.decodeCursor("ses_1234567890abcdef", cursor);

    assertThat(decoded.observedAt()).isEqualTo(observedAt);
    assertThat(decoded.connectionId()).isEqualTo("rdc_1234567890abcdefghij");
    assertThatThrownBy(
            () ->
                RemoteDesktopParticipantHistoryQueryRepository.decodeCursor(
                    "ses_other1234567890", cursor))
        .isInstanceOf(
            RemoteDesktopParticipantHistoryQueryRepository
                .InvalidRemoteDesktopParticipantCursorException.class);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void retentionDeletesOnlyBoundedTerminalRows() {
    var jdbc = Mockito.mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(any(String.class), any(java.util.Map.class))).thenReturn(17);
    var repository = new RemoteDesktopParticipantHistoryQueryRepository(jdbc);
    var cutoff = Instant.parse("2026-07-12T00:00:00Z");

    assertThat(repository.purgeTerminalBefore(cutoff, 20_000)).isEqualTo(17);

    var sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<java.util.Map<String, ?>> parameters =
        (ArgumentCaptor) ArgumentCaptor.forClass(java.util.Map.class);
    verify(jdbc).update(sql.capture(), parameters.capture());
    assertThat(sql.getValue()).contains("state IN ('REVOKED', 'DISCONNECTED')");
    assertThat(sql.getValue()).doesNotContain("CONNECTED', 'REVOKE_REQUESTED");
    assertThat(parameters.getValue().get("limit")).isEqualTo(10_000);
    assertThat(parameters.getValue()).containsKey("cutoff");
  }
}
