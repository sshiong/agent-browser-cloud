package io.browsercloud.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.infrastructure.NodeEventMapper;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.SessionRecordingFinalizedEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual wire mapping, production JDBC projection and separately committed migration phases. */
@EnabledIfEnvironmentVariable(named = "BROWSERCLOUD_RECORDING_RETENTION_TEST_URL", matches = ".+")
class RecordingRedactionVersionPostgresTest {
  private PGSimpleDataSource source;
  private JdbcTemplate jdbc;
  private SessionRecordingApplicationService service;
  private String legacy;
  private String legacyHash;

  @BeforeEach
  void setUp() throws Exception {
    var url = System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_URL");
    assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/recording_retention_test");
    source = new PGSimpleDataSource();
    source.setURL(url);
    source.setUser("recording_retention_test");
    source.setPassword(System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_PASSWORD"));
    source.setSocketFactory(
        RecordingRetentionOrderPostgresTest.OwnedLoopbackSocketFactory.class.getName());
    var schema = "redaction_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    source.setCurrentSchema(schema);
    jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY,tenant_id TEXT,profile_id TEXT)");
    var policy = Files.readString(migrations().resolve("V019__enterprise_operations.sql"));
    var start = policy.indexOf("CREATE TABLE enterprise_retention_policies (");
    jdbc.execute(policy.substring(start, policy.indexOf(");", start) + 2));
    migrate("V099__session_recording_manifest_projection.sql");
    migrate("V128__recording_retention_physical_deletion.sql");
    jdbc.update("INSERT INTO sessions VALUES('ses_owned','tenant-owned','profile-owned')");
    var sessions = mock(SessionRepository.class);
    when(sessions.require("ses_owned"))
        .thenReturn(
            new SessionContext(
                "ses_owned",
                "tenant-owned",
                "profile-owned",
                "node-owned",
                "runtime-owned",
                "sandbox",
                "",
                1,
                1,
                1,
                1,
                ResourceClass.L2,
                SessionState.RUNNING,
                "",
                Instant.EPOCH,
                Instant.EPOCH));
    service = new SessionRecordingApplicationService(jdbc, sessions, 31);
    legacy = id();
    project(legacy, 1);
    legacyHash = rowHash(legacy);
  }

  @Test
  void v2WireMetadataProjectsAfterExpandWithoutRewritingLegacyOrLeakingObjectKeys()
      throws Exception {
    var modern = id();
    assertThatThrownBy(() -> project(modern, 2))
        .isInstanceOf(DataIntegrityViolationException.class);
    migrate("V137__recording_redaction_v2_projection.sql");
    assertThat(validated()).isFalse();
    project(modern, 2);
    project(modern, 2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM session_recordings", Integer.class))
        .isEqualTo(2);
    assertThat(rowHash(legacy)).isEqualTo(legacyHash);
    migrate("V138__validate_recording_redaction_v2_projection.sql");
    assertThat(validated()).isTrue();
    var listed = service.list("ses_owned", "tenant-owned", 100, 0);
    assertThat(listed.items())
        .extracting(item -> item.redactionPolicyVersion())
        .containsExactlyInAnyOrder(1, 2);
    assertThat(listed.toString()).doesNotContain("tenants/tenant-owned/profiles/");
    assertThat(rowHash(legacy)).isEqualTo(legacyHash);
  }

  @Test
  void v2DoesNotRelaxUnknownVersionsHashSizeTimeOrRetentionGuards() throws Exception {
    migrate("V137__recording_redaction_v2_projection.sql");
    migrate("V138__validate_recording_redaction_v2_projection.sql");
    var modern = id();
    project(modern, 2);
    var original = rowHash(modern);
    for (var assignment :
        new String[] {
          "redaction_policy_version=0",
          "redaction_policy_version=3",
          "manifest_sha256='invalid'",
          "manifest_bytes=0",
          "ended_at=started_at-interval '1 second'",
          "retention_until=ended_at-interval '1 second'"
        }) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE session_recordings SET " + assignment + " WHERE recording_id=?",
                      modern))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(rowHash(modern)).isEqualTo(original);
    }
    assertThat(rowHash(legacy)).isEqualTo(legacyHash);
  }

  @Test
  void separateValidationTransactionAllowsConcurrentRecordingInsertion() throws Exception {
    migrate("V137__recording_redaction_v2_projection.sql");
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var validation =
          executor.submit(
              () -> {
                try (var connection = source.getConnection()) {
                  connection.setAutoCommit(false);
                  try (var statement = connection.createStatement()) {
                    statement.execute(
                        Files.readString(
                            migrations()
                                .resolve("V138__validate_recording_redaction_v2_projection.sql")));
                  }
                  locked.countDown();
                  assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                  connection.commit();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      try {
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL lock_timeout='250ms'");
              project(id(), 2);
            });
      } finally {
        release.countDown();
      }
      validation.get(5, TimeUnit.SECONDS);
    }
    assertThat(validated()).isTrue();
    assertThat(rowHash(legacy)).isEqualTo(legacyHash);
  }

  private void project(String recording, int version) {
    var payload =
        SessionRecordingFinalizedEvent.newBuilder()
            .setSessionId("ses_owned")
            .setRecordingId(recording)
            .setNodeId("node-owned")
            .setSegmentCount(1)
            .setFrameCount(3)
            .setRedactedFrameCount(3)
            .setRedactedRegionCount(3)
            .setRedactionPolicyVersion(version)
            .setManifestObjectKey(
                "tenants/tenant-owned/profiles/profile-owned/sessions/ses_owned/recordings/"
                    + recording
                    + "/COMMITTED")
            .setManifestSha256("a".repeat(64))
            .setManifestBytes(512)
            .setStartedAtMs(1785283100000L)
            .setEndedAtMs(1785283200000L)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_" + recording)
            .setTenantId("tenant-owned")
            .setSessionId("ses_owned")
            .setEventType("SessionRecordingFinalized")
            .setSequence(1)
            .setPayload(payload.toByteString())
            .build();
    var mapped = new NodeEventMapper().toCommand(envelope);
    service.record(
        mapped.tenantId(), mapped.eventId(), (NodeEvent.RecordingFinalized) mapped.event());
  }

  private String rowHash(String recording) {
    return jdbc.queryForObject(
        "SELECT md5(row_to_json(r)::text) FROM session_recordings r WHERE recording_id=?",
        String.class,
        recording);
  }

  private boolean validated() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT convalidated FROM pg_constraint WHERE conrelid='session_recordings'::regclass AND conname='chk_session_recording_manifest'",
            Boolean.class));
  }

  private void migrate(String file) throws Exception {
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(migrations().resolve(file)));
      }
      connection.commit();
    }
  }

  private static String id() {
    return "rec_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static Path migrations() {
    var directory = Path.of("").toAbsolutePath();
    while (directory != null && !Files.isDirectory(directory.resolve("database/migrations")))
      directory = directory.getParent();
    if (directory == null) throw new IllegalStateException("Repository migrations required");
    return directory.resolve("database/migrations");
  }
}
