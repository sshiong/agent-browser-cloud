package io.browsercloud.api;

import static io.browsercloud.api.AgentReviewerModels.*;

import io.browsercloud.application.AgentReviewerApplicationService;
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

/** Fixed model-review IPC surface available only to the isolated Reviewer Worker role. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AgentReviewerController {

  private final AgentReviewerApplicationService service;
  private final PlatformIdentity identity;

  public AgentReviewerController(
      AgentReviewerApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/agent-review-jobs:claim")
  public ResponseEntity<AgentReviewJobClaimView> claim(
      @Valid @RequestBody ClaimAgentReviewJobRequest request) {
    return service
        .claim(request, requireReviewerActor())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
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
