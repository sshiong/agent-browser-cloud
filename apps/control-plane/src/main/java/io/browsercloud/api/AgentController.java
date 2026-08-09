package io.browsercloud.api;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.application.AgentApplicationService;
import io.browsercloud.application.AgentExecutionWorkerApplicationService;
import io.browsercloud.application.AgentHumanGovernanceService;
import io.browsercloud.application.AgentReviewerApplicationService;
import io.browsercloud.application.AgentTaskSummaryApplicationService;
import io.browsercloud.application.CoordinatorCommandRoutingService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
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
  private final AgentTaskSummaryApplicationService summaryService;
  private final io.browsercloud.application.AgentExecutionService executionService;
  private final AgentExecutionWorkerApplicationService externalWorker;
  private final AgentReviewerApplicationService reviewer;
  private final AgentHumanGovernanceService governanceService;
  private final CoordinatorCommandRoutingService commandRouting;
  private final PlatformIdentity identity;

  public AgentController(
      AgentApplicationService service,
      AgentTaskSummaryApplicationService summaryService,
      io.browsercloud.application.AgentExecutionService executionService,
      AgentExecutionWorkerApplicationService externalWorker,
      AgentReviewerApplicationService reviewer,
      AgentHumanGovernanceService governanceService,
      CoordinatorCommandRoutingService commandRouting,
      PlatformIdentity identity) {
    this.service = service;
    this.summaryService = summaryService;
    this.executionService = executionService;
    this.externalWorker = externalWorker;
    this.reviewer = reviewer;
    this.governanceService = governanceService;
    this.commandRouting = commandRouting;
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

  @GetMapping("/agent-task-summaries")
  public AgentTaskSummaryListResponse listSummaries(
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
      @RequestParam(required = false) @Size(max = 512) String cursor) {
    return summaryService.list(identity.current().tenantId(), limit, cursor);
  }

  @PostMapping("/agent-tasks/{taskId}:execute")
  @PreAuthorize(PlatformRoles.OPERATE)
  public AgentTaskView execute(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
    var principal = identity.current();
    if (reviewer.enabled()) {
      reviewer.enqueueForExecution(taskId, principal.tenantId(), idempotencyKey);
      return service.get(taskId, principal.tenantId());
    }
    if (externalWorker.enabled()) {
      return externalWorker.enqueue(taskId, principal.tenantId(), idempotencyKey);
    }
    var task = service.get(taskId, principal.tenantId());
    return commandRouting.execute(
        task.sessionId(),
        principal.tenantId(),
        AGENT_EXECUTE,
        idempotencyKey,
        new AgentExecute(principal.tenantId(), taskId, idempotencyKey),
        AgentTaskView.class,
        () -> executionService.execute(taskId, principal.tenantId(), idempotencyKey));
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
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId,
      HttpServletRequest request) {
    var principal = identity.current();
    var task = service.get(taskId, principal.tenantId());
    return commandRouting.execute(
        task.sessionId(),
        principal.tenantId(),
        AGENT_ACCEPT_HANDOFF,
        String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
        new AgentHandoff(principal.tenantId(), taskId, principal.actorId()),
        AgentTaskView.class,
        () -> governanceService.acceptHandoff(taskId, principal.tenantId(), principal.actorId()));
  }

  @PostMapping("/agent-tasks/{taskId}:reject-handoff")
  @PreAuthorize(PlatformRoles.OPERATE)
  public AgentTaskView rejectHandoff(
      @PathVariable @Pattern(regexp = "^agt_[a-zA-Z0-9]{16,}$") String taskId) {
    var principal = identity.current();
    return governanceService.rejectHandoff(taskId, principal.tenantId(), principal.actorId());
  }
}
