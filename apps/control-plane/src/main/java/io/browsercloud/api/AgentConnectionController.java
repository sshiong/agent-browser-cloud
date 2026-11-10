package io.browsercloud.api;

import io.browsercloud.application.*;
import io.browsercloud.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent-connections")
@PreAuthorize(PlatformRoles.ADMIN)
public class AgentConnectionController {
  private final AgentConnectionAccessService access;
  private final AgentConnectionEnvironmentApplicationService environments;
  private final PlatformIdentity identity;
  private final AuditApplicationService audit;

  public AgentConnectionController(
      AgentConnectionAccessService access,
      AgentConnectionEnvironmentApplicationService environments,
      PlatformIdentity identity,
      AuditApplicationService audit) {
    this.access = access;
    this.environments = environments;
    this.identity = identity;
    this.audit = audit;
  }

  public record CreateConnection(
      String name,
      int maximumEnvironments,
      int maximumRunning,
      Instant expiresAt,
      List<String> allowedTools) {}

  public record ConfigureQuota(int maximumEnvironments, int maximumRunning) {}

  @GetMapping
  public List<AgentConnectionAccessService.Connection> list() {
    return access.list(identity.current().tenantId());
  }

  @PostMapping
  @Transactional
  public org.springframework.http.ResponseEntity<AgentConnectionAccessService.IssuedConnection>
      create(@RequestBody CreateConnection request) {
    var p = identity.current();
    var result =
        access.issue(
            p.tenantId(),
            request.name(),
            p.actorId(),
            request.maximumEnvironments(),
            request.maximumRunning(),
            request.expiresAt(),
            request.allowedTools());
    record(result.connection().connectionId(), "CREATED");
    return org.springframework.http.ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Pragma", "no-cache")
        .body(result);
  }

  @PatchMapping("/{connectionId}")
  @Transactional
  public Map<String, Boolean> configure(
      @PathVariable String connectionId, @RequestBody ConfigureQuota request) {
    var p = identity.current();
    access.configure(
        p.tenantId(), connectionId, request.maximumEnvironments(), request.maximumRunning());
    record(connectionId, "QUOTA_UPDATED");
    return Map.of("updated", true);
  }

  @PostMapping("/{connectionId}:revoke")
  @Transactional
  public Map<String, Boolean> revoke(@PathVariable String connectionId) {
    environments.revokeConnection(identity.current().tenantId(), connectionId);
    record(connectionId, "REVOKED");
    return Map.of("revoked", true);
  }

  private void record(String id, String action) {
    var p = identity.current();
    audit.append(
        new AuditApplicationService.AuditRecord(
            p.tenantId(),
            null,
            "AGENT_CONNECTION",
            "USER",
            p.actorId(),
            "AGENT_CONNECTION",
            id,
            action,
            "COMMITTED",
            Map.of(),
            UUID.randomUUID().toString()));
  }
}
