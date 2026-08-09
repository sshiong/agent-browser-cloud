package io.browsercloud.api;

import static io.browsercloud.api.AgentWorkerModels.*;

import io.browsercloud.application.AgentExecutionWorkerApplicationService;
import io.browsercloud.security.PlatformIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed, data-minimized IPC surface available only to the isolated Agent Worker role. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AgentWorkerController {

  private final AgentExecutionWorkerApplicationService service;
  private final PlatformIdentity identity;

  public AgentWorkerController(
      AgentExecutionWorkerApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/agent-worker-jobs:claim")
  public ResponseEntity<AgentExecutionJobClaimView> claim(
      @Valid @RequestBody ClaimAgentExecutionJobRequest request) {
    return service
        .claim(request, requireWorkerActor())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/agent-worker-jobs/{jobId}:start")
  public AgentExecutionJobView start(
      @PathVariable @Pattern(regexp = "^ajob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody AgentExecutionJobClaimRequest request) {
    return service.start(jobId, request, requireWorkerActor());
  }

  @PostMapping("/agent-worker-jobs/{jobId}:heartbeat")
  public AgentExecutionJobView heartbeat(
      @PathVariable @Pattern(regexp = "^ajob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody AgentExecutionJobClaimRequest request) {
    return service.heartbeat(jobId, request, requireWorkerActor());
  }

  @PostMapping("/agent-worker-jobs/{jobId}:drive")
  public AgentExecutionJobView drive(
      @PathVariable @Pattern(regexp = "^ajob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody AgentExecutionJobClaimRequest request) {
    return service.drive(jobId, request, requireWorkerActor());
  }

  @PostMapping("/agent-worker-jobs/{jobId}:fail")
  public AgentExecutionJobView fail(
      @PathVariable @Pattern(regexp = "^ajob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody FailAgentExecutionJobRequest request) {
    return service.fail(jobId, request, requireWorkerActor());
  }

  private String requireWorkerActor() {
    var principal = identity.current();
    if (!principal.roles().contains("AGENT_WORKER")
        && !principal.roles().contains("PLATFORM_ADMIN")) {
      throw new AccessDeniedException("AGENT_WORKER role is required");
    }
    return principal.actorId();
  }
}
