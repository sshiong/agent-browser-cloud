package io.browsercloud.api;

import io.browsercloud.application.AgentApplicationService;
import io.browsercloud.application.AgentHumanGovernanceService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class AgentController {

  private final AgentApplicationService service;
  private final io.browsercloud.application.AgentExecutionService executionService;
  private final AgentHumanGovernanceService governanceService;
  private final PlatformIdentity identity;

  public AgentController(
      AgentApplicationService service,
      io.browsercloud.application.AgentExecutionService executionService,
      AgentHumanGovernanceService governanceService,
      PlatformIdentity identity) {
    this.service = service;
    this.executionService = executionService;
    this.governanceService = governanceService;
    this.identity = identity;
  }

  @PostMapping("/sessions/{sessionId}/agent-tasks")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<AgentTaskView> create(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CreateAgentTaskRequest request) {
    return ResponseEntity.status(201)
        .body(service.create(sessionId, identity.current().tenantId(), request, idempotencyKey));
  }

  @GetMapping("/agent-tasks/{taskId}")
  public AgentTaskView get(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId) {
    return service.get(taskId, identity.current().tenantId());
  }

  @GetMapping("/agent-tasks")
  public AgentTaskListResponse list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return service.list(identity.current().tenantId(), limit, offset);
  }

  @PostMapping("/agent-tasks/{taskId}:execute")
  @PreAuthorize(PlatformRoles.OPERATE)
  public AgentTaskView execute(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
    return executionService.execute(taskId, identity.current().tenantId(), idempotencyKey);
  }

  @PostMapping("/agent-tasks/{taskId}:approve")
  @PreAuthorize(PlatformRoles.OPERATE)
  public AgentTaskView approve(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId) {
    var principal = identity.current();
    return governanceService.approveConfirmation(taskId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/agent-tasks/{taskId}:reject")
  @PreAuthorize(PlatformRoles.OPERATE)
  public AgentTaskView reject(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId) {
    var principal = identity.current();
    return governanceService.rejectConfirmation(taskId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/agent-tasks/{taskId}:accept-handoff")
  @PreAuthorize(PlatformRoles.OPERATE)
  public AgentTaskView acceptHandoff(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId) {
    var principal = identity.current();
    return governanceService.acceptHandoff(taskId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/agent-tasks/{taskId}:reject-handoff")
  @PreAuthorize(PlatformRoles.OPERATE)
  public AgentTaskView rejectHandoff(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId) {
    var principal = identity.current();
    return governanceService.rejectHandoff(taskId, principal.tenantId(), principal.actorId());
  }
}
