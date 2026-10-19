package io.browsercloud.application;

import static io.browsercloud.api.EnterpriseOperationsModels.*;
import static io.browsercloud.application.RecordingRetentionDeletionNodeGateway.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.SocketFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL gate; its dedicated runner supplies an owned loopback database. */
@EnabledIfEnvironmentVariable(named = "BROWSERCLOUD_RECORDING_RETENTION_TEST_URL", matches = ".+")
public class RecordingRetentionOrderPostgresTest {
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;
  private EnterpriseOperationsApplicationService governance;
  private RecordingRetentionDeletionApplicationService deletion;
  private RecordingRetentionDeletionNodeGateway gateway;
  private BrowserCapacityApplicationService capacity;
  private final AtomicBoolean remoteDispatched = new AtomicBoolean();

  @BeforeEach
  void setUp() throws Exception {
    var url = System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_URL");
    assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/recording_retention_test");
    var dataSource = new PGSimpleDataSource();
    dataSource.setURL(url);
    dataSource.setUser("recording_retention_test");
    dataSource.setPassword(System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_PASSWORD"));
    dataSource.setSocketFactory(OwnedLoopbackSocketFactory.class.getName());
    assertThat(dataSource.getServerNames()).containsExactly("127.0.0.1");
    assertThat(dataSource.getPortNumbers()).hasSize(1);
    var schema = "retention_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE SCHEMA " + schema);
    dataSource.setCurrentSchema(schema);
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY, tenant_id TEXT, profile_id TEXT)");
    executeTable("V019__enterprise_operations.sql", "enterprise_retention_policies");
    executeTable("V019__enterprise_operations.sql", "enterprise_regions");
    executeTable(
        "V021__enterprise_governance_evidence.sql", "enterprise_retention_deletion_receipts");
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      for (var migration :
          new String[] {
            "V099__session_recording_manifest_projection.sql",
            "V128__recording_retention_physical_deletion.sql",
            "V134__recording_durable_deletion_authority.sql"
          }) {
        try (var statement = connection.createStatement()) {
          statement.execute(Files.readString(migrations().resolve(migration)));
        }
      }
      connection.commit();
    }
    jdbc.update("INSERT INTO sessions VALUES ('session-test','tenant-test','profile-test')");
    jdbc.update(
        "INSERT INTO enterprise_regions VALUES ('primary','PRIMARY','OPEN',0,now(),'fixture')");
    jdbc.update(
        """
        INSERT INTO session_recordings(
          recording_id,event_id,tenant_id,session_id,node_id,segment_count,frame_count,
          dropped_frames,redacted_frame_count,redacted_region_count,redaction_policy_version,
          manifest_object_key,manifest_sha256,manifest_bytes,started_at,ended_at,retention_until)
        VALUES ('recording-test','event-test','tenant-test','session-test','node-test',1,1,
          0,1,1,1,'fixture-manifest',?,100,now()-interval '3 days',
          now()-interval '2 days',now()-interval '1 day')
        """,
        "a".repeat(64));
    var mapper = new ObjectMapper();
    var audit = mock(AuditApplicationService.class);
    capacity = mock(BrowserCapacityApplicationService.class);
    when(capacity.nodeHasCapability(anyString(), anyString(), anyString())).thenReturn(true);
    gateway = mock(RecordingRetentionDeletionNodeGateway.class);
    deletion =
        new RecordingRetentionDeletionApplicationService(
            jdbc,
            capacity,
            gateway,
            audit,
            mapper,
            10,
            new DataSourceTransactionManager(dataSource));
    governance =
        new EnterpriseOperationsApplicationService(
            jdbc,
            mapper,
            audit,
            mock(ReleaseFreezeApplicationService.class),
            mock(RecoveryGameDayGovernanceApplicationService.class),
            "owned-test-key",
            "owned-test",
            false,
            0,
            "local");
    transactions.executeWithoutResult(status -> assertThat(deletion.enqueueDue(1)).isEqualTo(1));
  }

  @Test
  void unknownRemoteResultMustNotBecomeASuccessfulLegalHold() {
    when(gateway.delete(any()))
        .thenAnswer(
            invocation -> {
              remoteDispatched.set(true);
              throw new RecordingDeletionNodeUnavailableException("owned-response-lost");
            });
    assertThat(deletion.processNext()).isTrue();
    assertThat(remoteDispatched).isTrue();
    assertHoldCannotPromisePreservation();
  }

  @Test
  void crashAfterDispatchMustNotEraseTheDeletionIntent() {
    when(gateway.delete(any()))
        .thenAnswer(
            invocation -> {
              remoteDispatched.set(true);
              throw new OwnedProcessCrash();
            });
    assertThatThrownBy(() -> deletion.processNext()).isInstanceOf(OwnedProcessCrash.class);
    assertThat(remoteDispatched).isTrue();
    assertHoldCannotPromisePreservation();
  }

  @Test
  void holdBeforeAuthorizationPreventsEveryRemoteDispatch() {
    transactions.executeWithoutResult(
        status ->
            governance.upsertRetention(
                "tenant-test",
                new UpsertRetentionPolicyRequest("REMOTE_DESKTOP_RECORDING", 30, true, "primary"),
                "test-admin"));
    assertThat(deletion.processNext()).isFalse();
    verifyNoInteractions(gateway);
    assertThat(
            jdbc.queryForObject(
                "SELECT deletion_authorized_at IS NULL FROM session_recordings", Boolean.class))
        .isTrue();
  }

  @Test
  void unavailableCapabilityDoesNotIrreversiblyAuthorizeDeletion() {
    when(capacity.nodeHasCapability(anyString(), anyString(), anyString())).thenReturn(false);
    assertThat(deletion.processNext()).isTrue();
    verifyNoInteractions(gateway);
    transactions.executeWithoutResult(
        status ->
            governance.upsertRetention(
                "tenant-test",
                new UpsertRetentionPolicyRequest("REMOTE_DESKTOP_RECORDING", 30, true, "primary"),
                "test-admin"));
    assertThat(jdbc.queryForObject("SELECT legal_hold FROM session_recordings", Boolean.class))
        .isTrue();
  }

  @Test
  void successfulGatewayResultCommitsReceiptAndNormalFutureHoldRemainsAvailable() {
    stubSuccessfulProof();
    assertThat(deletion.processNext()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM recording_retention_deletion_jobs", String.class))
        .isEqualTo("COMMITTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM enterprise_retention_deletion_receipts", Integer.class))
        .isEqualTo(1);
    transactions.executeWithoutResult(
        status ->
            governance.upsertRetention(
                "tenant-test",
                new UpsertRetentionPolicyRequest("REMOTE_DESKTOP_RECORDING", 30, true, "primary"),
                "test-admin"));
  }

  @Test
  void abandonedExecutionRecoversWithNewEpochAndTheSameDurableIntent() {
    when(gateway.delete(any())).thenThrow(new OwnedProcessCrash());
    assertThatThrownBy(() -> deletion.processNext()).isInstanceOf(OwnedProcessCrash.class);
    var authorizedAt =
        jdbc.queryForObject(
            "SELECT deletion_authorized_at FROM session_recordings", java.sql.Timestamp.class);
    assertThat(authorizedAt).isNotNull();
    jdbc.update(
        "UPDATE recording_retention_deletion_jobs SET started_at = now() - interval '3 minutes'");
    stubSuccessfulProof();
    assertThat(deletion.processNext()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT execution_epoch FROM recording_retention_deletion_jobs", Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT deletion_authorized_at FROM session_recordings", java.sql.Timestamp.class))
        .isEqualTo(authorizedAt);
  }

  @Test
  void exhaustedCrashRemainsUncertainWithoutInventingAReceipt() {
    jdbc.update("UPDATE recording_retention_deletion_jobs SET maximum_attempts = 1");
    when(gateway.delete(any())).thenThrow(new OwnedProcessCrash());
    assertThatThrownBy(() -> deletion.processNext()).isInstanceOf(OwnedProcessCrash.class);
    jdbc.update(
        "UPDATE recording_retention_deletion_jobs SET started_at = now() - interval '3 minutes'");
    assertThat(deletion.processNext()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM recording_retention_deletion_jobs", String.class))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM enterprise_retention_deletion_receipts", Integer.class))
        .isZero();
    assertHoldCannotPromisePreservation();
  }

  @Test
  void olderWorkerCannotDispatchWithoutDurableAuthority() {
    assertThatThrownBy(
            () -> jdbc.update("UPDATE recording_retention_deletion_jobs SET state = 'EXECUTING'"))
        .hasMessageContaining("RECORDING_DURABLE_DELETION_AUTHORITY_REQUIRED");
    verifyNoInteractions(gateway);
  }

  @Test
  void olderPolicyWriterAndDirectProjectionCannotPromisePreservation() {
    when(gateway.delete(any()))
        .thenThrow(new RecordingDeletionNodeUnavailableException("owned-lost"));
    deletion.processNext();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        INSERT INTO enterprise_retention_policies VALUES
          ('tenant-test','REMOTE_DESKTOP_RECORDING',30,true,'primary','old-writer',now())
        """))
        .hasMessageContaining("RECORDING_DELETION_ALREADY_AUTHORIZED");
    assertThatThrownBy(() -> jdbc.update("UPDATE session_recordings SET legal_hold = true"))
        .hasMessageContaining("RECORDING_DELETION_ALREADY_AUTHORIZED");
    assertThatThrownBy(
            () -> jdbc.update("UPDATE session_recordings SET deletion_authorized_at = NULL"))
        .hasMessageContaining("RECORDING_DELETION_ALREADY_AUTHORIZED");
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        governance.upsertRetention(
                            "tenant-test",
                            new UpsertRetentionPolicyRequest(
                                "REMOTE_DESKTOP_RECORDING", 30, false, "primary"),
                            "test-admin")))
        .isInstanceOf(EnterpriseOperationsApplicationService.GovernanceRejectedException.class);
  }

  @Test
  void migrationRefusesToInferSafetyFromLegacyQueueEvenAtZeroAttempts() throws Exception {
    var migration =
        Files.readString(migrations().resolve("V134__recording_durable_deletion_authority.sql"));
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.execute(migration)))
        .hasMessageContaining("RECORDING_LEGACY_DELETION_RECONCILIATION_REQUIRED");
  }

  @Test
  void policyCommittedBeforeClaimWinsAcrossTwoConnections() throws Exception {
    var held = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      try {
        var policy =
            executor.submit(
                () ->
                    transactions.executeWithoutResult(
                        status -> {
                          governance.upsertRetention(
                              "tenant-test",
                              new UpsertRetentionPolicyRequest(
                                  "REMOTE_DESKTOP_RECORDING", 30, true, "primary"),
                              "test-admin");
                          held.countDown();
                          await(release);
                        }));
        assertThat(held.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var started = new java.util.concurrent.CountDownLatch(1);
        var worker =
            executor.submit(
                () -> {
                  started.countDown();
                  return deletion.processNext();
                });
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> worker.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        release.countDown();
        policy.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(worker.get(5, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        verifyNoInteractions(gateway);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void holdDuringDispatchedDeletionRejectsWithoutClaimingSuccess() throws Exception {
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      doAnswer(
              invocation -> {
                // The RPC transaction is still open on the caller; this is a distinct database
                // connection.
                executor
                    .submit(this::assertHoldCannotPromisePreservation)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
                throw new RecordingDeletionNodeUnavailableException("owned-response-lost");
              })
          .when(gateway)
          .delete(any());
      assertThat(deletion.processNext()).isTrue();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM recording_retention_deletion_jobs", String.class))
        .isEqualTo("RETRY");
  }

  @Test
  void concurrentOldIngestCannotLoseCommittedPolicyHold() throws Exception {
    var held = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      try {
        var policy =
            executor.submit(
                () ->
                    transactions.executeWithoutResult(
                        status -> {
                          governance.upsertRetention(
                              "tenant-test",
                              new UpsertRetentionPolicyRequest(
                                  "REMOTE_DESKTOP_RECORDING", 30, true, "primary"),
                              "test-admin");
                          held.countDown();
                          await(release);
                        }));
        assertThat(held.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var started = new java.util.concurrent.CountDownLatch(1);
        var ingest =
            executor.submit(
                () -> {
                  started.countDown();
                  return jdbc.update(
                      """
              INSERT INTO session_recordings(
                recording_id,event_id,tenant_id,session_id,node_id,segment_count,frame_count,
                dropped_frames,redacted_frame_count,redacted_region_count,redaction_policy_version,
                manifest_object_key,manifest_sha256,manifest_bytes,started_at,ended_at,retention_until,legal_hold)
              SELECT 'recording-late','event-late',tenant_id,session_id,node_id,segment_count,frame_count,
                dropped_frames,redacted_frame_count,redacted_region_count,redaction_policy_version,
                manifest_object_key,manifest_sha256,manifest_bytes,started_at,ended_at,
                ended_at + interval '1 day',false FROM session_recordings WHERE recording_id = 'recording-test'
              """);
                });
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> ingest.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        release.countDown();
        policy.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ingest.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(
                jdbc.queryForObject(
                    "SELECT legal_hold AND retention_until >= ended_at + interval '30 days' FROM session_recordings WHERE recording_id = 'recording-late'",
                    Boolean.class))
            .isTrue();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void anotherTenantCanStillSetItsHold() {
    when(gateway.delete(any()))
        .thenThrow(new RecordingDeletionNodeUnavailableException("owned-lost"));
    deletion.processNext();
    transactions.executeWithoutResult(
        status ->
            governance.upsertRetention(
                "tenant-other",
                new UpsertRetentionPolicyRequest("REMOTE_DESKTOP_RECORDING", 30, true, "primary"),
                "test-admin"));
    assertThat(
            jdbc.queryForObject(
                "SELECT legal_hold FROM enterprise_retention_policies WHERE tenant_id = 'tenant-other'",
                Boolean.class))
        .isTrue();
  }

  private static void await(java.util.concurrent.CountDownLatch latch) {
    try {
      if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        throw new AssertionError("Owned latch timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Owned latch interrupted", exception);
    }
  }

  private void stubSuccessfulProof() {
    // doAnswer replaces even a prior Error-throwing stub without invoking it during setup.
    doAnswer(
            invocation -> {
              var request = invocation.getArgument(0, RecordingDeletionRequest.class);
              return new RecordingDeletionProof(
                  request.jobId(),
                  request.deletionEpoch(),
                  request.nodeId(),
                  request.recordingId(),
                  "b".repeat(64),
                  3,
                  java.time.Instant.now());
            })
        .when(gateway)
        .delete(any());
  }

  private void assertHoldCannotPromisePreservation() {
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        governance.upsertRetention(
                            "tenant-test",
                            new UpsertRetentionPolicyRequest(
                                "REMOTE_DESKTOP_RECORDING", 30, true, "primary"),
                            "test-admin")))
        .isInstanceOf(EnterpriseOperationsApplicationService.GovernanceRejectedException.class)
        .hasMessage("RECORDING_DELETION_ALREADY_AUTHORIZED");
    assertThat(jdbc.queryForObject("SELECT legal_hold FROM session_recordings", Boolean.class))
        .isFalse();
  }

  private void executeTable(String file, String table) throws Exception {
    var source = Files.readString(migrations().resolve(file));
    var start = source.indexOf("CREATE TABLE " + table + " (");
    assertThat(start).isGreaterThanOrEqualTo(0);
    jdbc.execute(source.substring(start, source.indexOf(");", start) + 2));
  }

  private static Path migrations() {
    var directory = Path.of("").toAbsolutePath();
    while (directory != null && !Files.isDirectory(directory.resolve("database/migrations"))) {
      directory = directory.getParent();
    }
    if (directory == null) throw new IllegalStateException("Repository migrations are required");
    return directory.resolve("database/migrations");
  }

  private static final class OwnedProcessCrash extends Error {}

  /** Never changes JVM-wide proxy settings or permits an external database target. */
  public static final class OwnedLoopbackSocketFactory extends SocketFactory {
    @Override
    public Socket createSocket() {
      return new Socket(Proxy.NO_PROXY) {
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
          if (!(endpoint instanceof InetSocketAddress address)
              || !"127.0.0.1".equals(address.getHostString())) {
            throw new IOException("Only the owned loopback database is permitted");
          }
          super.connect(
              new InetSocketAddress(
                  InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), address.getPort()),
              timeout);
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      throw unsupported();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort)
        throws IOException {
      throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
        throws IOException {
      throw unsupported();
    }

    private IOException unsupported() {
      return new IOException("Use the owned unconnected loopback socket");
    }
  }
}
