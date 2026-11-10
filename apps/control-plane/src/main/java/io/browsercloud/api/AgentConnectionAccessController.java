package io.browsercloud.api;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.application.*;
import io.browsercloud.application.AgentConnectionAccessService.Identity;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent-access")
public class AgentConnectionAccessController {
  private final AgentConnectionEnvironmentApplicationService environments;
  private final SessionApplicationService sessions;
  private final SessionDeletionApplicationService deletion;
  private final CoordinatorCommandRoutingService routing;
  private final JdbcTemplate jdbc;

  public AgentConnectionAccessController(
      AgentConnectionEnvironmentApplicationService environments,
      SessionApplicationService sessions,
      SessionDeletionApplicationService deletion,
      CoordinatorCommandRoutingService routing,
      JdbcTemplate jdbc) {
    this.environments = environments;
    this.sessions = sessions;
    this.deletion = deletion;
    this.routing = routing;
    this.jdbc = jdbc;
  }

  private Identity who(Authentication authentication, String tool) {
    var i = (Identity) authentication.getPrincipal();
    if (!i.tools().contains(tool))
      throw new AgentConnectionEnvironmentApplicationService.ConnectionRejectedException(
          "AGENT_TOOL_FORBIDDEN");
    return i;
  }

  @GetMapping("/identity")
  public Object identity(Authentication authentication) {
    var i = (Identity) authentication.getPrincipal();
    return Map.of(
        "tenantId", i.tenantId(), "connectionId", i.connectionId(), "allowedTools", i.tools());
  }

  @GetMapping("/quota")
  public Object quota(Authentication authentication) {
    var i = who(authentication, "quota");
    return environments.quota(i.tenantId(), i.subject(), i.connectionId());
  }

  @GetMapping("/environments")
  public Object list(
      Authentication authentication,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    var i = who(authentication, "list");
    return Map.of(
        "items",
        environments.environments(i.tenantId(), i.subject(), i.connectionId(), limit, offset),
        "limit",
        limit,
        "offset",
        offset);
  }

  @GetMapping("/environments/{sessionId}")
  public Object get(Authentication authentication, @PathVariable String sessionId) {
    var i = who(authentication, "get");
    return environments.environment(i.tenantId(), i.subject(), i.connectionId(), sessionId);
  }

  @PostMapping("/environments")
  public Object create(
      Authentication authentication,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateSessionRequest request) {
    var i = who(authentication, "create");
    return environments.createEnvironment(
        i.tenantId(), i.subject(), i.connectionId(), key, request, UUID.randomUUID().toString());
  }

  @PostMapping("/environments/{sessionId}:start")
  public Object start(
      Authentication authentication,
      @PathVariable String sessionId,
      @RequestHeader("Idempotency-Key") String key) {
    var i = who(authentication, "start");
    environments.environment(i.tenantId(), i.subject(), i.connectionId(), sessionId);
    return routing.execute(
        sessionId,
        i.tenantId(),
        SESSION_START,
        commandKey(i, sessionId, "start", key),
        new SessionActor(i.tenantId(), i.subject()),
        OperationResponse.class,
        () -> sessions.start(sessionId, i.tenantId(), i.subject()));
  }

  @PostMapping("/environments/{sessionId}:stop")
  public Object stop(
      Authentication authentication,
      @PathVariable String sessionId,
      @RequestHeader("Idempotency-Key") String key) {
    var i = who(authentication, "stop");
    environments.environment(i.tenantId(), i.subject(), i.connectionId(), sessionId);
    return routing.execute(
        sessionId,
        i.tenantId(),
        SESSION_STOP,
        commandKey(i, sessionId, "stop", key),
        new SessionActor(i.tenantId(), i.subject()),
        OperationResponse.class,
        () -> sessions.stop(sessionId, i.tenantId(), i.subject()));
  }

  @DeleteMapping("/environments/{sessionId}")
  public Object delete(
      Authentication authentication,
      @PathVariable String sessionId,
      @RequestHeader("Idempotency-Key") String key) {
    var i = who(authentication, "delete");
    environments.quota(i.tenantId(), i.subject(), i.connectionId());
    var owned =
        jdbc.queryForObject(
            "SELECT count(*) FROM agent_connection_environments WHERE tenant_id=? AND connection_id=? AND session_id=?",
            Long.class,
            i.tenantId(),
            i.connectionId(),
            sessionId);
    if (owned == null || owned != 1L)
      throw new AgentConnectionEnvironmentApplicationService.ConnectionRejectedException(
          "AGENT_ENVIRONMENT_NOT_FOUND");
    return deletion.delete(
        i.tenantId(),
        i.subject(),
        new SessionDeletionModels.BatchDeleteSessionsRequest(List.of(sessionId)),
        commandKey(i, sessionId, "delete", key),
        UUID.randomUUID().toString());
  }

  @GetMapping("/operations/{operationId}")
  public Object operation(Authentication authentication, @PathVariable String operationId) {
    var i = who(authentication, "operation");
    environments.quota(i.tenantId(), i.subject(), i.connectionId());
    var rows =
        jdbc.query(
            "SELECT o.operation_id,o.session_id,o.state,o.phase FROM exclusive_operations o JOIN agent_connection_environments e ON e.session_id=o.session_id WHERE o.operation_id=? AND e.tenant_id=? AND e.connection_id=?",
            (r, n) ->
                Map.of(
                    "operationId",
                    r.getString(1),
                    "sessionId",
                    r.getString(2),
                    "state",
                    r.getString(3),
                    "phase",
                    r.getString(4)),
            operationId,
            i.tenantId(),
            i.connectionId());
    if (rows.size() != 1)
      throw new AgentConnectionEnvironmentApplicationService.ConnectionRejectedException(
          "AGENT_OPERATION_NOT_FOUND");
    return rows.getFirst();
  }

  private String commandKey(Identity i, String session, String action, String key) {
    if (key.isBlank() || key.length() > 128)
      throw new IllegalArgumentException("AGENT_IDEMPOTENCY_KEY_INVALID");
    try {
      return "mcp-"
          + java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(
                          (i.connectionId() + ":" + session + ":" + action + ":" + key)
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid() {
    return ResponseEntity.badRequest().body(Map.of("code", "AGENT_REQUEST_INVALID"));
  }

  @ExceptionHandler(AgentConnectionEnvironmentApplicationService.ConnectionRejectedException.class)
  ResponseEntity<?> rejected(
      AgentConnectionEnvironmentApplicationService.ConnectionRejectedException error) {
    return ResponseEntity.status(403).body(Map.of("code", error.getMessage()));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<?> quotaRejected() {
    return ResponseEntity.status(409).body(Map.of("code", "AGENT_QUOTA_OR_IDENTITY_CONFLICT"));
  }
}
