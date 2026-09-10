package io.browsercloud.api;

import static io.browsercloud.api.AgentOutcomeVerifierModels.*;

import io.browsercloud.application.AgentOutcomeVerifierApplicationService;
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

/** IPC surface available only to the isolated semantic Outcome Verifier Worker role. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AgentOutcomeVerifierController {

  private final AgentOutcomeVerifierApplicationService service;
  private final PlatformIdentity identity;

  public AgentOutcomeVerifierController(
      AgentOutcomeVerifierApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/agent-outcome-jobs:claim")
  public ResponseEntity<AgentOutcomeJobClaimView> claim(
      @Valid @RequestBody ClaimAgentOutcomeJobRequest request) {
    return service
        .claim(request, requireWorkerActor())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/agent-outcome-jobs/{jobId}:start")
  public AgentOutcomeJobView start(
      @PathVariable @Pattern(regexp = "^ojob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody AgentOutcomeJobClaimRequest request) {
    return service.start(jobId, request, requireWorkerActor());
  }

  @PostMapping("/agent-outcome-jobs/{jobId}:heartbeat")
  public AgentOutcomeJobView heartbeat(
      @PathVariable @Pattern(regexp = "^ojob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody AgentOutcomeJobClaimRequest request) {
    return service.heartbeat(jobId, request, requireWorkerActor());
  }

  @PostMapping("/agent-outcome-jobs/{jobId}:complete")
  public AgentOutcomeJobView complete(
      @PathVariable @Pattern(regexp = "^ojob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody CompleteAgentOutcomeJobRequest request) {
    return service.complete(jobId, request, requireWorkerActor());
  }

  @PostMapping("/agent-outcome-jobs/{jobId}:fail")
  public AgentOutcomeJobView fail(
      @PathVariable @Pattern(regexp = "^ojob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody FailAgentOutcomeJobRequest request) {
    return service.fail(jobId, request, requireWorkerActor());
  }

  private String requireWorkerActor() {
    var principal = identity.current();
    if (!principal.roles().contains("OUTCOME_VERIFIER_WORKER")
        && !principal.roles().contains("PLATFORM_ADMIN")) {
      throw new AccessDeniedException("OUTCOME_VERIFIER_WORKER role is required");
    }
    return principal.actorId();
  }
}
