package io.browsercloud.api;

import static io.browsercloud.api.AgentReviewerModels.*;

import io.browsercloud.application.AgentReviewerApplicationService;
import io.browsercloud.application.WorkerQueueWakeupService;
import io.browsercloud.security.PlatformIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fixed model-review IPC surface available only to the isolated Reviewer Worker role. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AgentReviewerController {

  private final AgentReviewerApplicationService service;
  private final WorkerQueueWakeupService wakeups;
  private final PlatformIdentity identity;

  public AgentReviewerController(
      AgentReviewerApplicationService service,
      WorkerQueueWakeupService wakeups,
      PlatformIdentity identity) {
    this.service = service;
    this.wakeups = wakeups;
    this.identity = identity;
  }

  @PostMapping("/agent-review-jobs:claim")
  public CompletableFuture<ResponseEntity<AgentReviewJobClaimView>> claim(
      @Valid @RequestBody ClaimAgentReviewJobRequest request,
      @RequestParam(defaultValue = "0") @Min(0) @Max(WorkerQueueWakeupService.MAX_WAIT_SECONDS)
          int waitSeconds) {
    var actorId = requireReviewerActor();
    return wakeups
        .claim(
            WorkerQueueWakeupService.AGENT_REVIEW,
            waitSeconds,
            () -> service.claim(request, actorId),
            Optional::isPresent)
        .thenApply(
            claimed ->
                claimed
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build()));
  }

  @PostMapping("/agent-review-jobs/{jobId}:start")
  public AgentReviewJobView start(
      @PathVariable @Pattern(regexp = "^rjob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody AgentReviewJobClaimRequest request) {
    return service.start(jobId, request, requireReviewerActor());
  }

  @PostMapping("/agent-review-jobs/{jobId}:heartbeat")
  public AgentReviewJobView heartbeat(
      @PathVariable @Pattern(regexp = "^rjob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody AgentReviewJobClaimRequest request) {
    return service.heartbeat(jobId, request, requireReviewerActor());
  }

  @PostMapping("/agent-review-jobs/{jobId}:complete")
  public AgentReviewJobView complete(
      @PathVariable @Pattern(regexp = "^rjob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody CompleteAgentReviewJobRequest request) {
    return service.complete(jobId, request, requireReviewerActor());
  }

  @PostMapping("/agent-review-jobs/{jobId}:fail")
  public AgentReviewJobView fail(
      @PathVariable @Pattern(regexp = "^rjob_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody FailAgentReviewJobRequest request) {
    return service.fail(jobId, request, requireReviewerActor());
  }

  private String requireReviewerActor() {
    var principal = identity.current();
    if (!principal.roles().contains("REVIEWER_WORKER")
        && !principal.roles().contains("PLATFORM_ADMIN")) {
      throw new AccessDeniedException("REVIEWER_WORKER role is required");
    }
    return principal.actorId();
  }
}
