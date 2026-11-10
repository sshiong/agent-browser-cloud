package io.browsercloud.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.api.CreateSessionResponse;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "BROWSERCLOUD_RECORDING_RETENTION_TEST_URL", matches = ".+")
class AgentConnectionEnvironmentPostgresTest {
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  SessionApplicationService sessions;
  AgentConnectionEnvironmentApplicationService service;
  String connection;
  final JsonMapper mapper = JsonMapper.builder().build();

  @BeforeEach
  void setup() throws Exception {
    var url = System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_URL");
    assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/recording_retention_test");
    var source = new PGSimpleDataSource();
    source.setURL(url);
    source.setUser("recording_retention_test");
    source.setPassword(System.getenv("BROWSERCLOUD_RECORDING_RETENTION_TEST_PASSWORD"));
    source.setSocketFactory(
        RecordingRetentionOrderPostgresTest.OwnedLoopbackSocketFactory.class.getName());
    var schema = "mcp_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    source.setCurrentSchema(schema);
    jdbc = new JdbcTemplate(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    jdbc.execute(
        "CREATE TABLE sessions(id TEXT PRIMARY KEY,tenant_id TEXT DEFAULT 'tenant',state TEXT DEFAULT 'CREATED',deleted_at TIMESTAMPTZ)");
    var root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("database/migrations")))
      root = root.getParent();
    assertThat(root).isNotNull();
    var migration =
        Files.readString(
            root.resolve("database/migrations/V139__agent_connection_quota_authority.sql"));
    tx.executeWithoutResult(status -> jdbc.execute(migration));
    var accessMigration =
        Files.readString(
            root.resolve("database/migrations/V140__agent_connection_access_and_lifecycle.sql"));
    tx.executeWithoutResult(status -> jdbc.execute(accessMigration));
    sessions = mock(SessionApplicationService.class);
    service =
        new AgentConnectionEnvironmentApplicationService(
            jdbc, sessions, mock(EntityManager.class), mapper);
    connection =
        tx.execute(
            status ->
                service.createConnection(
                    "tenant", "Agent", "subject", 1, 1, Instant.now().plusSeconds(3600)));
  }

  CreateSessionRequest request(String profile) throws Exception {
    return mapper.readValue(
        "{\"tenantId\":\"tenant\",\"profileId\":\""
            + profile
            + "\",\"resourcePolicy\":{\"mode\":\"AUTO\"}}",
        CreateSessionRequest.class);
  }

  @Test
  void opaqueCredentialIsOneTimeIssuedScopedRevocableAndNeverListed() {
    var access = new AgentConnectionAccessService(jdbc, service);
    var issued =
        tx.execute(
            status ->
                access.issue(
                    "tenant",
                    "Limited",
                    "subject",
                    3,
                    1,
                    Instant.now().plusSeconds(3600),
                    java.util.List.of("list", "get")));
    assertThat(issued.accessToken()).startsWith("abc_mcp_");
    var authenticated = access.authenticate(issued.accessToken());
    assertThat(authenticated.connectionId()).isEqualTo(issued.connection().connectionId());
    assertThat(authenticated.tools()).containsExactlyInAnyOrder("list", "get");
    assertThat(access.authenticate("abc_mcp_" + "x".repeat(43))).isNull();
    assertThat(access.list("tenant").toString()).doesNotContain(issued.accessToken());
    assertThat(
            jdbc.queryForObject(
                "SELECT credential_sha256 FROM agent_connections WHERE connection_id=?",
                String.class,
                issued.connection().connectionId()))
        .doesNotContain(issued.accessToken())
        .hasSize(64);
    tx.executeWithoutResult(
        status -> service.revokeConnection("tenant", issued.connection().connectionId()));
    assertThat(access.authenticate(issued.accessToken())).isNull();
  }

  @Test
  void bindsCreationAndReplaysWithoutCreatingAgainOrExposingAnotherPrincipal() throws Exception {
    when(sessions.create(any(), anyString(), eq("subject"), anyString(), eq(false)))
        .thenAnswer(
            call -> {
              jdbc.update("INSERT INTO sessions(id) VALUES('ses_owned')");
              return new CreateSessionResponse("ses_owned", "op_owned", "CREATED", null, null);
            });
    var request = request("profile");
    var first =
        tx.execute(
            status ->
                service.createEnvironment(
                    "tenant", "subject", connection, "key", request, "trace"));
    assertThat(first.sessionId()).isEqualTo("ses_owned");
    assertThat(first.replay()).isFalse();
    var second =
        tx.execute(
            status ->
                service.createEnvironment(
                    "tenant", "subject", connection, "key", request, "trace2"));
    assertThat(second.sessionId()).isEqualTo(first.sessionId());
    assertThat(second.replay()).isTrue();
    var view = mock(io.browsercloud.api.SessionView.class);
    when(sessions.get("ses_owned", "tenant")).thenReturn(view);
    var detail =
        tx.execute(status -> service.environment("tenant", "subject", connection, "ses_owned"));
    var page = tx.execute(status -> service.environments("tenant", "subject", connection, 20, 0));
    var emptyPage =
        tx.execute(status -> service.environments("tenant", "subject", connection, 20, 1));
    assertThat(detail).isSameAs(view);
    assertThat(page).containsExactly(view);
    assertThat(emptyPage).isEmpty();
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> service.environment("tenant", "subject", connection, "ses_other")))
        .hasMessageContaining("ENVIRONMENT_NOT_FOUND");
    verify(sessions, never()).get("ses_other", "tenant");
    verify(sessions, times(1)).create(any(), anyString(), eq("subject"), anyString(), eq(false));
    var changed = request("different");
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        service.createEnvironment(
                            "tenant", "subject", connection, "key", changed, "trace")))
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> tx.execute(status -> service.quota("tenant", "other", connection)))
        .hasMessageContaining("ACCESS_REJECTED");
    assertThatThrownBy(() -> tx.execute(status -> service.quota("other", "subject", connection)))
        .hasMessageContaining("ACCESS_REJECTED");
    assertThat(
            tx.execute(status -> service.quota("tenant", "subject", connection)).environmentsUsed())
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT session_id FROM agent_connection_environments", String.class))
        .isEqualTo("ses_owned");
  }

  @Test
  void rollsBackReservationAndSessionOnCreationFailureAndRevocationRejectsFurtherAccess()
      throws Exception {
    when(sessions.create(any(), anyString(), anyString(), anyString(), eq(false)))
        .thenAnswer(
            call -> {
              jdbc.update("INSERT INTO sessions(id) VALUES('ses_failed')");
              throw new IllegalStateException("OWNED_CREATE_FAILURE");
            });
    var request = request("profile");
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        service.createEnvironment(
                            "tenant", "subject", connection, "key", request, "trace")))
        .hasMessageContaining("OWNED_CREATE_FAILURE");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isZero();
    assertThat(
            tx.execute(status -> service.quota("tenant", "subject", connection)).environmentsUsed())
        .isZero();
    tx.executeWithoutResult(status -> service.revokeConnection("tenant", connection));
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        service.createEnvironment(
                            "tenant", "subject", connection, "key", request, "trace")))
        .hasMessageContaining("ACCESS_REJECTED");
  }
}
