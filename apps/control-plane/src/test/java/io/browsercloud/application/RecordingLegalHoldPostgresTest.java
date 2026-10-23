package io.browsercloud.application;

import static io.browsercloud.application.RecordingLegalHoldNodeGateway.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual service/transaction/migration gate. Gateway receipts here are explicit test fixtures. */
@EnabledIfEnvironmentVariable(named = "BROWSERCLOUD_RECORDING_RETENTION_TEST_URL", matches = ".+")
class RecordingLegalHoldPostgresTest {
  private JdbcTemplate jdbc;
  private RecordingLegalHoldApplicationService service;
  private RecordingLegalHoldNodeGateway gateway;
  private BrowserCapacityApplicationService capacity;
  private AuditApplicationService audit;
  private DataSourceTransactionManager manager;

  @BeforeEach
  void setUp() throws Exception {
    var source = new PGSimpleDataSource();
    var url = System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_URL");
    assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/recording_retention_test");
    source.setURL(url);
    source.setUser("recording_retention_test");
    source.setPassword(System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_PASSWORD"));
    source.setSocketFactory(
        RecordingRetentionOrderPostgresTest.OwnedLoopbackSocketFactory.class.getName());
    var schema = "hold_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    source.setCurrentSchema(schema);
    jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY, tenant_id TEXT, profile_id TEXT)");
    table("V019__enterprise_operations.sql", "enterprise_retention_policies");
    table("V102__enterprise_overview_event_stream.sql", "enterprise_overview_events");
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      for (var migration :
          new String[] {
            "V099__session_recording_manifest_projection.sql",
            "V128__recording_retention_physical_deletion.sql",
            "V134__recording_durable_deletion_authority.sql",
            "V135__recording_cloud_hold_authority.sql"
          }) {
        try (var statement = connection.createStatement()) {
          statement.execute(Files.readString(migrations().resolve(migration)));
        }
      }
      connection.commit();
    }
    jdbc.update("INSERT INTO sessions VALUES('ses_owned','tenant-owned','profile-owned')");
    jdbc.update(
        """
        INSERT INTO session_recordings(recording_id,event_id,tenant_id,session_id,node_id,segment_count,frame_count,
          dropped_frames,redacted_frame_count,redacted_region_count,redaction_policy_version,manifest_object_key,
          manifest_sha256,manifest_bytes,started_at,ended_at,retention_until)
          VALUES('rec_owned','event-owned','tenant-owned','ses_owned','node-owned',1,1,0,1,1,1,
          'owned-manifest',?,100,now()-interval '3 days',now()-interval '2 days',now()-interval '1 day')
        """,
        "a".repeat(64));
    capacity = mock(BrowserCapacityApplicationService.class);
    when(capacity.nodeHasCapability(anyString(), anyString(), anyString())).thenReturn(true);
    gateway = mock(RecordingLegalHoldNodeGateway.class);
    doAnswer(invocation -> proof(invocation.getArgument(0))).when(gateway).apply(any());
    audit = mock(AuditApplicationService.class);
    manager = new DataSourceTransactionManager(source);
    service = new RecordingLegalHoldApplicationService(jdbc, capacity, gateway, audit, manager);
    jdbc.update(
        """
        INSERT INTO enterprise_retention_policies(tenant_id,data_class,retention_days,legal_hold,
          residency_region,updated_by,updated_at,cloud_hold_required)
          VALUES('tenant-owned','REMOTE_DESKTOP_RECORDING',1,true,'primary','owned',now(),true)
        """);
  }

  @Test
  void dispatchSeesCommittedLeaseAndOnlyExactOffProofReleases() {
    doAnswer(
            invocation -> {
              assertThat(
                      text("SELECT state FROM recording_cloud_hold_jobs WHERE state='EXECUTING'"))
                  .isEqualTo("EXECUTING");
              assertThat(
                      number(
                          "SELECT lease_epoch FROM recording_cloud_hold_jobs WHERE state='EXECUTING'"))
                  .isEqualTo(1);
              return proof(invocation.getArgument(0));
            })
        .when(gateway)
        .apply(any());
    assertThat(service.processNext()).isTrue();
    assertThat(text("SELECT status FROM recording_cloud_hold_authorities"))
        .isEqualTo("VERIFIED_ON");
    policy(false);
    assertHeld();
    assertThat(service.processNext()).isTrue();
    assertThat(jdbc.queryForObject("SELECT legal_hold FROM session_recordings", Boolean.class))
        .isFalse();
    assertThat(text("SELECT status FROM recording_cloud_hold_authorities"))
        .isEqualTo("VERIFIED_OFF");
    verify(audit, times(2)).append(any());
  }

  @Test
  void lostResultReconcilesTheSameRequestBeforeQueueingTheNewDesiredMode() {
    var original = new AtomicReference<HoldRequest>();
    doAnswer(
            invocation -> {
              original.set(invocation.getArgument(0));
              throw new HoldResultUnknownException("owned-response-lost");
            })
        .when(gateway)
        .apply(any());
    assertThat(service.processNext()).isTrue();
    assertUnknown();
    policy(false);
    doAnswer(
            invocation -> {
              assertThat((HoldRequest) invocation.getArgument(0)).isEqualTo(original.get());
              return proof(invocation.getArgument(0));
            })
        .when(gateway)
        .apply(any());
    available();
    assertThat(service.processNext()).isTrue();
    assertHeld();
    assertThat(text("SELECT mode FROM recording_cloud_hold_jobs WHERE state='QUEUED'"))
        .isEqualTo("OFF");
    assertThat(number("SELECT lease_epoch FROM recording_cloud_hold_jobs WHERE state='COMMITTED'"))
        .isEqualTo(2);
    doAnswer(invocation -> proof(invocation.getArgument(0))).when(gateway).apply(any());
    assertThat(service.processNext()).isTrue();
    assertThat(text("SELECT status FROM recording_cloud_hold_authorities"))
        .isEqualTo("VERIFIED_OFF");
  }

  @Test
  void processCrashKeepsTheDurableAttemptAndRecoversWithoutChangingBusinessEpoch() {
    var original = new AtomicReference<HoldRequest>();
    doAnswer(
            invocation -> {
              original.set(invocation.getArgument(0));
              throw new OwnedCrash();
            })
        .when(gateway)
        .apply(any());
    assertThatThrownBy(() -> service.processNext()).isInstanceOf(OwnedCrash.class);
    assertThat(text("SELECT state FROM recording_cloud_hold_jobs")).isEqualTo("EXECUTING");
    assertThat(number("SELECT attempt FROM recording_cloud_hold_jobs")).isEqualTo(1);
    jdbc.update("UPDATE recording_cloud_hold_jobs SET started_at=now()-interval '3 minutes'");
    doAnswer(
            invocation -> {
              assertThat((HoldRequest) invocation.getArgument(0)).isEqualTo(original.get());
              return proof(invocation.getArgument(0));
            })
        .when(gateway)
        .apply(any());
    assertThat(service.processNext()).isTrue();
    assertThat(number("SELECT lease_epoch FROM recording_cloud_hold_jobs")).isEqualTo(2);
    assertThat(number("SELECT authority_epoch FROM recording_cloud_hold_jobs")).isEqualTo(1);
  }

  @Test
  void absentCapabilityKeepsPendingWithoutConsumingAnExecutionAttempt() {
    when(capacity.nodeHasCapability(anyString(), anyString(), anyString())).thenReturn(false);
    assertThat(service.processNext()).isTrue();
    verifyNoInteractions(gateway);
    assertThat(text("SELECT state FROM recording_cloud_hold_jobs")).isEqualTo("QUEUED");
    assertThat(number("SELECT attempt FROM recording_cloud_hold_jobs")).isZero();
    assertHeld();
  }

  @Test
  void exhaustedCrashedLeaseStaysUnknownAndDoesNotDispatchAgain() {
    jdbc.update(
        "UPDATE recording_cloud_hold_jobs SET state='EXECUTING',attempt=10,lease_epoch=10,started_at=now()-interval '3 minutes'");
    assertThat(service.processNext()).isTrue();
    assertUnknown();
    assertThat(text("SELECT failure_code FROM recording_cloud_hold_jobs"))
        .isEqualTo("RECORDING_HOLD_RECONCILIATION_EXHAUSTED");
    assertThat(service.processNext()).isFalse();
    verifyNoInteractions(gateway);
  }

  @Test
  void finalUnknownReconciliationIsExplicitlyExhaustedWithoutAFalseFailureOrRelease() {
    jdbc.update(
        "UPDATE recording_cloud_hold_jobs SET state='EXECUTING',attempt=9,lease_epoch=9,started_at=now()-interval '3 minutes'");
    jdbc.update("UPDATE recording_cloud_hold_jobs SET state='UNKNOWN'");
    jdbc.update("UPDATE recording_cloud_hold_authorities SET status='UNKNOWN'");
    doThrow(new HoldResultUnknownException("owned-result-still-unknown"))
        .when(gateway)
        .apply(any());
    assertThat(service.processNext()).isTrue();
    assertUnknown();
    assertThat(number("SELECT attempt FROM recording_cloud_hold_jobs")).isEqualTo(10);
    assertThat(text("SELECT failure_code FROM recording_cloud_hold_jobs"))
        .isEqualTo("RECORDING_HOLD_RECONCILIATION_EXHAUSTED");
    assertThat(service.processNext()).isFalse();
    verify(gateway, times(1)).apply(any());
  }

  @Test
  void invalidReceiptDoesNotProduceAppliedProof() {
    doAnswer(
            invocation -> {
              HoldRequest request = invocation.getArgument(0);
              return new HoldProof(
                  request.operationId(),
                  request.authorityEpoch() + 1,
                  request.nodeId(),
                  request.recordingId(),
                  request.mode(),
                  "b".repeat(64),
                  "c".repeat(64),
                  3,
                  Instant.now());
            })
        .when(gateway)
        .apply(any());
    assertThat(service.processNext()).isTrue();
    assertUnknown();
    assertThat(
            jdbc.queryForObject(
                "SELECT proof_hash IS NULL FROM recording_cloud_hold_authorities", Boolean.class))
        .isTrue();
  }

  @Test
  void auditCommitFailureCannotEraseAnAlreadyDispatchedAttempt() {
    doThrow(new IllegalStateException("owned-audit-failure")).when(audit).append(any());
    assertThatThrownBy(() -> service.processNext()).isInstanceOf(IllegalStateException.class);
    assertThat(text("SELECT state FROM recording_cloud_hold_jobs")).isEqualTo("EXECUTING");
    assertHeld();
    assertThat(
            jdbc.queryForObject(
                "SELECT proof_hash IS NULL FROM recording_cloud_hold_jobs", Boolean.class))
        .isTrue();
    reset(audit);
    jdbc.update("UPDATE recording_cloud_hold_jobs SET started_at=now()-interval '3 minutes'");
    assertThat(service.processNext()).isTrue();
    assertThat(text("SELECT status FROM recording_cloud_hold_authorities"))
        .isEqualTo("VERIFIED_ON");
  }

  @Test
  void twoWorkersCannotOverlapAnInFlightRequest() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return proof(invocation.getArgument(0));
            })
        .when(gateway)
        .apply(any());
    try (var pool = Executors.newSingleThreadExecutor()) {
      var running = pool.submit(() -> service.processNext());
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(service.processNext()).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(running.get(10, TimeUnit.SECONDS)).isTrue();
    }
    verify(gateway, times(1)).apply(any());
  }

  @Test
  void staleWorkerCannotDispatchAfterANewerLeaseHasCommitted() throws Exception {
    var oldManager = new PausingCommit(manager);
    var newManager = new PausingCommit(manager);
    var oldService =
        new RecordingLegalHoldApplicationService(jdbc, capacity, gateway, audit, oldManager);
    var newService =
        new RecordingLegalHoldApplicationService(jdbc, capacity, gateway, audit, newManager);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var oldRun = pool.submit(() -> oldService.processNext());
      try {
        assertThat(oldManager.committed.await(10, TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE recording_cloud_hold_jobs SET started_at=now()-interval '3 minutes'");
        var newRun = pool.submit(() -> newService.processNext());
        try {
          assertThat(newManager.committed.await(10, TimeUnit.SECONDS)).isTrue();
          assertThat(number("SELECT lease_epoch FROM recording_cloud_hold_jobs")).isEqualTo(2);
          oldManager.resume.countDown();
          assertThat(oldRun.get(10, TimeUnit.SECONDS)).isTrue();
          verifyNoInteractions(gateway);
        } finally {
          newManager.resume.countDown();
        }
        assertThat(newRun.get(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        oldManager.resume.countDown();
        newManager.resume.countDown();
      }
    }
    verify(gateway, times(1)).apply(any());
    assertThat(text("SELECT status FROM recording_cloud_hold_authorities"))
        .isEqualTo("VERIFIED_ON");
  }

  /** Pauses after the real commit and resource cleanup; no production test hook is needed. */
  private static class PausingCommit
      implements org.springframework.transaction.PlatformTransactionManager {
    private final org.springframework.transaction.PlatformTransactionManager delegate;
    private final java.util.concurrent.atomic.AtomicBoolean first =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    final CountDownLatch committed = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);

    PausingCommit(org.springframework.transaction.PlatformTransactionManager delegate) {
      this.delegate = delegate;
    }

    public org.springframework.transaction.TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      return delegate.getTransaction(definition);
    }

    public void rollback(org.springframework.transaction.TransactionStatus status) {
      delegate.rollback(status);
    }

    public void commit(org.springframework.transaction.TransactionStatus status) {
      delegate.commit(status);
      if (first.compareAndSet(true, false)) {
        committed.countDown();
        try {
          if (!resume.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("Owned commit pause timed out");
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(exception);
        }
      }
    }
  }

  private static HoldProof proof(HoldRequest request) {
    return new HoldProof(
        request.operationId(),
        request.authorityEpoch(),
        request.nodeId(),
        request.recordingId(),
        request.mode(),
        (request.mode() == HoldMode.ON ? "b" : "d").repeat(64),
        "c".repeat(64),
        3,
        Instant.now());
  }

  private void policy(boolean wanted) {
    jdbc.update("UPDATE enterprise_retention_policies SET legal_hold=?", wanted);
  }

  private void available() {
    jdbc.update(
        "UPDATE recording_cloud_hold_jobs SET available_at=now()-interval '1 second' WHERE state='UNKNOWN'");
  }

  private void assertHeld() {
    assertThat(jdbc.queryForObject("SELECT legal_hold FROM session_recordings", Boolean.class))
        .isTrue();
  }

  private void assertUnknown() {
    assertHeld();
    assertThat(text("SELECT status FROM recording_cloud_hold_authorities")).isEqualTo("UNKNOWN");
    assertThat(text("SELECT state FROM recording_cloud_hold_jobs WHERE state<>'COMMITTED'"))
        .isEqualTo("UNKNOWN");
  }

  private String text(String sql) {
    return jdbc.queryForObject(sql, String.class);
  }

  private long number(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private void table(String migration, String name) throws Exception {
    var source = Files.readString(migrations().resolve(migration));
    var start = source.indexOf("CREATE TABLE " + name + " (");
    assertThat(start).isGreaterThanOrEqualTo(0);
    jdbc.execute(source.substring(start, source.indexOf("\n);", start) + 3));
  }

  private static Path migrations() {
    var current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("database/migrations")))
      current = current.getParent();
    if (current == null) throw new IllegalStateException("Owned repository migrations required");
    return current.resolve("database/migrations");
  }

  private static class OwnedCrash extends Error {}
}
