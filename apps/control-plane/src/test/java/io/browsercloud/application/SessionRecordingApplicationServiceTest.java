package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SessionRecordingApplicationServiceTest {
  private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
  private final SessionRepository sessions = Mockito.mock(SessionRepository.class);
  private final SessionRecordingApplicationService service =
      new SessionRecordingApplicationService(jdbc, sessions);

  @BeforeEach
  void configureSession() {
    when(sessions.require("ses_test"))
        .thenReturn(
            new SessionContext(
                "ses_test",
                "tenant-test",
                "profile-test",
                "node-test",
                "runtime-test",
                "sandbox",
                "proxy-test",
                1,
                2,
                1,
                1,
                ResourceClass.L2,
                SessionState.RUNNING,
                "policy",
                Instant.EPOCH,
                Instant.EPOCH));
  }

  @Test
  void shouldProjectNodeManifestWithoutChangingItsStorageIdentity() {
    var recording = recording();

    service.record("tenant-test", "evt-recording", recording);

    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), any(Object[].class));
    assertThat(sql.getValue())
        .contains("ON CONFLICT DO NOTHING")
        .contains("REMOTE_DESKTOP_RECORDING");
  }

  @Test
  void shouldExposeMetadataWithoutReturningObjectStorageCoordinates() throws Exception {
    var result = Mockito.mock(ResultSet.class);
    when(result.getString("recording_id")).thenReturn("rec_1234567890abcdef1234567890abcdef");
    when(result.getString("node_id")).thenReturn("node-test");
    when(result.getLong(anyString())).thenReturn(1L);
    when(result.getInt("redaction_policy_version")).thenReturn(1);
    when(result.getString("manifest_sha256")).thenReturn("a".repeat(64));
    when(result.getTimestamp(anyString())).thenReturn(Timestamp.from(Instant.EPOCH));
    when(result.getBoolean("legal_hold")).thenReturn(true);
    when(jdbc.query(
            anyString(), any(RowMapper.class), eq("tenant-test"), eq("ses_test"), eq(100), eq(0)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(result, 0));
            });

    var response = service.list("ses_test", "tenant-test", 500, -1);

    assertThat(response.limit()).isEqualTo(100);
    assertThat(response.offset()).isZero();
    assertThat(response.items())
        .singleElement()
        .satisfies(item -> assertThat(item.legalHold()).isTrue());
    assertThat(response.items().getFirst().toString()).doesNotContain("tenants/");
  }

  private static NodeEvent.RecordingFinalized recording() {
    return new NodeEvent.RecordingFinalized(
        "ses_test",
        "rec_1234567890abcdef1234567890abcdef",
        "node-test",
        1,
        20,
        0,
        2,
        2,
        1,
        "tenants/tenant-test/profiles/profile-test/sessions/ses_test/recordings/rec/COMMITTED",
        "a".repeat(64),
        512,
        1_785_283_100_000L,
        1_785_283_200_000L);
  }
}
