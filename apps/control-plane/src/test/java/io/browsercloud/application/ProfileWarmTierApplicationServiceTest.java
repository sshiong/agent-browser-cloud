package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileJpaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProfileWarmTierApplicationServiceTest {

  private JdbcTemplate jdbc;
  private ProfileJpaRepository profiles;
  private AuditApplicationService audit;
  private ProfileWarmTierApplicationService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    profiles = mock(ProfileJpaRepository.class);
    audit = mock(AuditApplicationService.class);
    service = new ProfileWarmTierApplicationService(jdbc, profiles, audit);
  }

  @Test
  void recordsTheNextCommittedBarrierAndAuditsOnlyMetadata() {
    when(profiles.findById("profile-test")).thenReturn(Optional.of(profile(4)));
    when(jdbc.query(
            anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(new long[] {4, 6}));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    var command = command(event(4, 7));

    service.record(command, (NodeEvent.ProfileWarmTierSynced) command.event());

    verify(jdbc).update(anyString(), any(Object[].class));
    verify(audit).append(any());
  }

  @Test
  void acceptsAMonotonicGapWhenAnOldContextBarrierWasTerminallyDiscarded() {
    when(profiles.findById("profile-test")).thenReturn(Optional.of(profile(4)));
    when(jdbc.query(
            anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(new long[] {4, 6}));

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    service.record(command(event(4, 8)), event(4, 8));

    verify(jdbc).update(anyString(), any(Object[].class));
  }

  @Test
  void projectsTheLatestWarmTierStatus() {
    when(profiles.findById("profile-test")).thenReturn(Optional.of(profile(4)));
    when(jdbc.query(
            anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              var mapper =
                  (org.springframework.jdbc.core.RowMapper<
                          ProfileWarmTierApplicationService.WarmTierStatus>)
                      invocation.getArgument(1);
              var result = mock(java.sql.ResultSet.class);
              when(result.getString(1)).thenReturn("node_test");
              when(result.getLong(2)).thenReturn(4L);
              when(result.getLong(3)).thenReturn(7L);
              when(result.getString(4)).thenReturn("wtb_4_7_1234567890abcdef");
              when(result.getLong(5)).thenReturn(2L);
              when(result.getLong(6)).thenReturn(1L);
              when(result.getLong(7)).thenReturn(8L);
              when(result.getLong(8)).thenReturn(4096L);
              when(result.getLong(9)).thenReturn(2L);
              when(result.getString(10)).thenReturn("a".repeat(64));
              when(result.getTimestamp(11))
                  .thenReturn(Timestamp.from(Instant.parse("2026-08-13T00:00:00Z")));
              return List.of(mapper.mapRow(result, 0));
            });

    var status = service.status("tenant-test", "profile-test");

    assertThat(status.state()).isEqualTo("LIVE");
    assertThat(status.journalSequence()).isEqualTo(7);
    assertThat(status.uploadedBytes()).isEqualTo(4096);
  }

  private static ProfileEntity profile(long writeEpoch) {
    var now = Instant.parse("2026-08-13T00:00:00Z");
    var profile = new ProfileEntity("profile-test", "tenant-test", "Profile", null, "storage", now);
    if (writeEpoch > 0) {
      profile.commitCheckpoint("chk_1234567890abcdef", 1, writeEpoch, 100, 2, "EMPTY", now);
    }
    return profile;
  }

  private static NodeEvent.ProfileWarmTierSynced event(long writeEpoch, long sequence) {
    return new NodeEvent.ProfileWarmTierSynced(
        "ses_test",
        "node_test",
        "profile-test",
        writeEpoch,
        sequence,
        "wtb_4_7_1234567890abcdef",
        2,
        1,
        8,
        4096,
        2,
        "a".repeat(64),
        1_786_576_800_000L);
  }

  private static NodeEventReceived command(NodeEvent.ProfileWarmTierSynced event) {
    return new NodeEventReceived("evt_warm_tier", "tenant-test", "ses_test", 1, 2, 0, 3, event);
  }
}
