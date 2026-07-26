package io.browsercloud.api;

import io.browsercloud.application.AgentApplicationService;
import io.browsercloud.application.AgentHumanGovernanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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
public class AgentController {

  private final AgentApplicationService service;
  private final io.browsercloud.application.AgentExecutionService executionService;
  private final AgentHumanGovernanceService governanceService;

  public AgentController(
      AgentApplicationService service,
      io.browsercloud.application.AgentExecutionService executionService,
      AgentHumanGovernanceService governanceService) {
    this.service = service;
    this.executionService = executionService;
    this.governanceService = governanceService;
  }

  @PostMapping("/sessions/{sessionId}/agent-tasks")
  public ResponseEntity<AgentTaskView> create(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CreateAgentTaskRequest request) {
    return ResponseEntity.status(201)
        .body(service.create(sessionId, tenantId, request, idempotencyKey));
  }

  @GetMapping("/agent-tasks/{taskId}")
  public AgentTaskView get(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId) {
    return service.get(taskId, tenantId);
  }

  @GetMapping("/agent-tasks")
  public AgentTaskListResponse list(
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return service.list(tenantId, limit, offset);
  }

  @PostMapping("/agent-tasks/{taskId}:execute")
  public AgentTaskView execute(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
    return executionService.execute(taskId, tenantId, idempotencyKey);
  }

  @PostMapping("/agent-tasks/{taskId}:approve")
  public AgentTaskView approve(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("X-Actor-Id") @NotBlank @Size(max = 128) String actorId) {
    return governanceService.approveConfirmation(taskId, tenantId, actorId);
  }

  @PostMapping("/agent-tasks/{taskId}:reject")
  public AgentTaskView reject(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("X-Actor-Id") @NotBlank @Size(max = 128) String actorId) {
    return governanceService.rejectConfirmation(taskId, tenantId, actorId);
  }

  @PostMapping("/agent-tasks/{taskId}:accept-handoff")
  public AgentTaskView acceptHandoff(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("X-Actor-Id") @NotBlank @Size(max = 128) String actorId) {
    return governanceService.acceptHandoff(taskId, tenantId, actorId);
  }

  @PostMapping("/agent-tasks/{taskId}:reject-handoff")
  public AgentTaskView rejectHandoff(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("X-Actor-Id") @NotBlank @Size(max = 128) String actorId) {
    return governanceService.rejectHandoff(taskId, tenantId, actorId);
  }
}
