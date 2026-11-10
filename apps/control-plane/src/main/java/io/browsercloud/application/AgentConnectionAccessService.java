package io.browsercloud.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentConnectionAccessService {
  public static final Set<String> TOOLS =
      Set.of("quota", "list", "get", "create", "start", "stop", "delete", "operation");
  private final JdbcTemplate jdbc;
  private final AgentConnectionEnvironmentApplicationService environments;

  public AgentConnectionAccessService(
      JdbcTemplate jdbc, AgentConnectionEnvironmentApplicationService environments) {
    this.jdbc = jdbc;
    this.environments = environments;
  }

  public record Identity(String tenantId, String subject, String connectionId, Set<String> tools) {}

  public record Connection(
      String connectionId,
      String name,
      String subject,
      int maximumEnvironments,
      int maximumRunning,
      Instant expiresAt,
      boolean enabled,
      List<String> allowedTools) {}

  public record IssuedConnection(Connection connection, String accessToken) {}

  @Transactional
  public IssuedConnection issue(
      String tenant,
      String name,
      String subject,
      int total,
      int running,
      Instant expiry,
      List<String> tools) {
    var allowed = tools == null ? TOOLS : new HashSet<>(tools);
    if (allowed.isEmpty()
        || !TOOLS.containsAll(allowed)
        || (tools != null && allowed.size() != tools.size()))
      throw new IllegalArgumentException("AGENT_CONNECTION_TOOLS_INVALID");
    var id = environments.createConnection(tenant, name, subject, total, running, expiry);
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    var token = "abc_mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update(
        connection -> {
          var ps =
              connection.prepareStatement(
                  "UPDATE agent_connections SET credential_sha256=?,allowed_tools=? WHERE connection_id=? AND tenant_id=?");
          ps.setString(1, hash(token));
          ps.setArray(2, connection.createArrayOf("text", allowed.toArray()));
          ps.setString(3, id);
          ps.setString(4, tenant);
          return ps;
        });
    return new IssuedConnection(
        list(tenant).stream().filter(c -> c.connectionId().equals(id)).findFirst().orElseThrow(),
        token);
  }

  public Identity authenticate(String token) {
    if (token == null || !token.matches("abc_mcp_[A-Za-z0-9_-]{43}")) return null;
    var rows =
        jdbc.query(
            "SELECT tenant_id,principal_subject,connection_id,allowed_tools FROM agent_connections WHERE credential_sha256=? AND enabled AND expires_at>now()",
            (r, n) ->
                new Identity(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    Set.copyOf(Arrays.asList((String[]) r.getArray(4).getArray()))),
            hash(token));
    return rows.size() == 1 ? rows.getFirst() : null;
  }

  public List<Connection> list(String tenant) {
    return jdbc.query(
        "SELECT connection_id,display_name,principal_subject,maximum_environments,maximum_running,expires_at,enabled,allowed_tools FROM agent_connections WHERE tenant_id=? ORDER BY created_at,connection_id",
        (r, n) ->
            new Connection(
                r.getString(1),
                r.getString(2),
                r.getString(3),
                r.getInt(4),
                r.getInt(5),
                r.getTimestamp(6).toInstant(),
                r.getBoolean(7),
                Arrays.asList((String[]) r.getArray(8).getArray())),
        tenant);
  }

  @Transactional
  public void configure(String tenant, String id, int total, int running) {
    if (total < 0 || total > 10000 || running < 0 || running > 10000)
      throw new IllegalArgumentException("AGENT_CONNECTION_QUOTA_INVALID");
    if (jdbc.update(
            "UPDATE agent_connections SET maximum_environments=?,maximum_running=? WHERE connection_id=? AND tenant_id=?",
            total,
            running,
            id,
            tenant)
        != 1) throw new IllegalArgumentException("AGENT_CONNECTION_NOT_FOUND");
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("AGENT_CREDENTIAL_HASH_FAILED");
    }
  }
}
