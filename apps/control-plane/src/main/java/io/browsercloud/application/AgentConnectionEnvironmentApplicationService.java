package io.browsercloud.application;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.api.CreateSessionResponse;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal MCP application boundary; transport/API authentication is connected separately. */
@Service
public class AgentConnectionEnvironmentApplicationService {
  private final JdbcTemplate jdbc;
  private final SessionApplicationService sessions;
  private final EntityManager entities;
  private final ObjectMapper canonical;

  public AgentConnectionEnvironmentApplicationService(
      JdbcTemplate jdbc,
      SessionApplicationService sessions,
      EntityManager entities,
      ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.entities = entities;
    this.canonical =
        mapper
            .copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Transactional
  public String createConnection(
      String tenant,
      String name,
      String subject,
      int maximumEnvironments,
      int maximumRunning,
      Instant expiresAt) {
    if (tenant == null
        || !tenant.matches("[A-Za-z0-9_-]{1,128}")
        || name == null
        || name.isBlank()
        || name.length() > 128
        || subject == null
        || subject.isBlank()
        || subject.length() > 512
        || maximumEnvironments < 0
        || maximumEnvironments > 10000
        || maximumRunning < 0
        || maximumRunning > 10000
        || expiresAt == null
        || !expiresAt.isAfter(Instant.now())) {
      throw new IllegalArgumentException("AGENT_CONNECTION_CONFIGURATION_INVALID");
    }
    var id = "acon_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        """
        INSERT INTO agent_connections(connection_id,tenant_id,display_name,principal_subject,
          maximum_environments,maximum_running,expires_at) VALUES(?,?,?,?,?,?,?)
        """,
        id,
        tenant,
        name,
        subject,
        maximumEnvironments,
        maximumRunning,
        java.sql.Timestamp.from(expiresAt));
    return id;
  }

  @Transactional
  public void revokeConnection(String tenant, String connection) {
    if (jdbc.update(
            "UPDATE agent_connections SET enabled=false WHERE connection_id=? AND tenant_id=?",
            connection,
            tenant)
        != 1) throw new ConnectionRejectedException("AGENT_CONNECTION_NOT_FOUND");
  }

  @Transactional
  public EnvironmentCreation createEnvironment(
      String tenant,
      String subject,
      String connection,
      String requestKey,
      CreateSessionRequest request,
      String traceId) {
    requireConnection(tenant, subject, connection);
    if (request == null
        || !tenant.equals(request.tenantId())
        || requestKey == null
        || requestKey.isBlank()
        || requestKey.length() > 128) {
      throw new ConnectionRejectedException("AGENT_ENVIRONMENT_REQUEST_INVALID");
    }
    var fingerprint = hash(request);
    var previous =
        jdbc.query(
            """
        SELECT request_sha256,session_id FROM agent_connection_environments
         WHERE connection_id=? AND request_key=?
        """,
            (r, n) -> new String[] {r.getString(1), r.getString(2)},
            connection,
            requestKey);
    if (!previous.isEmpty()) {
      if (!fingerprint.equals(previous.getFirst()[0]))
        throw new ConnectionRejectedException("AGENT_ENVIRONMENT_IDEMPOTENCY_CONFLICT");
      var session = previous.getFirst()[1];
      if (session == null)
        throw new ConnectionRejectedException("AGENT_ENVIRONMENT_CREATION_UNRESOLVED");
      return new EnvironmentCreation(session, null, true);
    }
    var reservation = "aenv_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        """
        INSERT INTO agent_connection_environments(reservation_id,connection_id,tenant_id,
          request_key,request_sha256,state) VALUES(?,?,?,?,?,'CREATING')
        """,
        reservation,
        connection,
        tenant,
        requestKey,
        fingerprint);
    // A connection-specific namespace prevents collision with ordinary API idempotency keys.
    var key = "mcp-" + hash(connection + ":" + requestKey);
    var result = sessions.create(request, key, subject, traceId, false);
    // Session persistence uses JPA; flush before the JDBC ownership FK is inserted in this same tx.
    entities.flush();
    jdbc.update(
        """
        UPDATE agent_connection_environments SET session_id=?,state='CREATED'
         WHERE reservation_id=? AND tenant_id=? AND connection_id=?
        """,
        result.sessionId(),
        reservation,
        tenant,
        connection);
    return new EnvironmentCreation(result.sessionId(), result, false);
  }

  @Transactional
  public Quota quota(String tenant, String subject, String connection) {
    requireConnection(tenant, subject, connection);
    return jdbc.queryForObject(
        """
        SELECT maximum_environments,maximum_running,
          (SELECT count(*) FROM agent_connection_environments e
            WHERE e.connection_id=c.connection_id AND e.state<>'DELETED') AS environments_used,
          (SELECT count(*) FROM agent_connection_environments e
            WHERE e.connection_id=c.connection_id AND e.state<>'DELETED' AND running_reserved) AS running_used
          FROM agent_connections c WHERE connection_id=? AND tenant_id=?
        """,
        (r, n) -> new Quota(r.getInt(1), r.getInt(2), r.getLong(3), r.getLong(4)),
        connection,
        tenant);
  }

  @Transactional
  public io.browsercloud.api.SessionView environment(
      String tenant, String subject, String connection, String session) {
    requireConnection(tenant, subject, connection);
    if (jdbc.queryForObject(
            """
        SELECT count(*) FROM agent_connection_environments
         WHERE tenant_id=? AND connection_id=? AND session_id=? AND state<>'DELETED'
        """,
            Long.class,
            tenant,
            connection,
            session)
        != 1L) {
      throw new ConnectionRejectedException("AGENT_ENVIRONMENT_NOT_FOUND");
    }
    return sessions.get(session, tenant);
  }

  @Transactional
  public java.util.List<io.browsercloud.api.SessionView> environments(
      String tenant, String subject, String connection, int limit, int offset) {
    requireConnection(tenant, subject, connection);
    if (limit < 1 || limit > 100 || offset < 0) {
      throw new ConnectionRejectedException("AGENT_ENVIRONMENT_PAGINATION_INVALID");
    }
    var ids =
        jdbc.query(
            """
        SELECT session_id FROM agent_connection_environments
         WHERE tenant_id=? AND connection_id=? AND state<>'DELETED' AND session_id IS NOT NULL
         ORDER BY created_at,reservation_id LIMIT ? OFFSET ?
        """,
            (r, n) -> r.getString(1),
            tenant,
            connection,
            limit,
            offset);
    return ids.stream().map(id -> sessions.get(id, tenant)).toList();
  }

  private void requireConnection(String tenant, String subject, String connection) {
    var found =
        jdbc.query(
            """
        SELECT principal_subject,enabled,expires_at FROM agent_connections
         WHERE connection_id=? AND tenant_id=? FOR UPDATE
        """,
            (r, n) ->
                r.getString(1).equals(subject)
                    && r.getBoolean(2)
                    && r.getTimestamp(3).toInstant().isAfter(Instant.now()),
            connection,
            tenant);
    if (found.size() != 1 || !found.getFirst())
      throw new ConnectionRejectedException("AGENT_CONNECTION_ACCESS_REJECTED");
  }

  private String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalArgumentException("AGENT_ENVIRONMENT_REQUEST_UNSERIALIZABLE");
    }
  }

  public record EnvironmentCreation(
      String sessionId, CreateSessionResponse created, boolean replay) {}

  public record Quota(
      int maximumEnvironments, int maximumRunning, long environmentsUsed, long runningUsed) {}

  public static final class ConnectionRejectedException extends RuntimeException {
    public ConnectionRejectedException(String reason) {
      super(reason);
    }
  }
}
