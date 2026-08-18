package io.browsercloud.api;

import static io.browsercloud.api.ChallengeAutomationModels.*;

import io.browsercloud.application.ChallengeAutomationApplicationService;
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

/** Fixed least-privilege IPC surface for isolated screenshot OCR/vision workers. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ChallengeVisualWorkerController {

  private final ChallengeAutomationApplicationService service;
  private final PlatformIdentity identity;

  public ChallengeVisualWorkerController(
      ChallengeAutomationApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/challenge-visual-jobs:claim")
  public ResponseEntity<ChallengeVisualJobClaimView> claim(
      @Valid @RequestBody ClaimChallengeVisualJobRequest request) {
    return service
        .claim(request, requireWorkerActor())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/challenge-visual-jobs/{jobId}:start")
  public ChallengeVisualJobView start(
      @PathVariable @Pattern(regexp = "^cvj_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody ChallengeVisualJobClaimRequest request) {
    return service.start(jobId, request, requireWorkerActor());
  }

  @PostMapping("/challenge-visual-jobs/{jobId}:heartbeat")
  public ChallengeVisualJobView heartbeat(
      @PathVariable @Pattern(regexp = "^cvj_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody ChallengeVisualJobClaimRequest request) {
    return service.heartbeat(jobId, request, requireWorkerActor());
  }

  @PostMapping("/challenge-visual-jobs/{jobId}:complete")
  public ChallengeVisualJobView complete(
      @PathVariable @Pattern(regexp = "^cvj_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody CompleteChallengeVisualJobRequest request) {
    return service.complete(jobId, request, requireWorkerActor());
  }

  @PostMapping("/challenge-visual-jobs/{jobId}:fail")
  public ChallengeVisualJobView fail(
      @PathVariable @Pattern(regexp = "^cvj_[A-Za-z0-9]{20}$") String jobId,
      @Valid @RequestBody FailChallengeVisualJobRequest request) {
    return service.fail(jobId, request, requireWorkerActor());
  }

  private String requireWorkerActor() {
    var principal = identity.current();
    if (!principal.roles().contains("VISION_WORKER")
        && !principal.roles().contains("PLATFORM_ADMIN")) {
      throw new AccessDeniedException("VISION_WORKER role is required");
    }
    return principal.actorId();
  }
}
