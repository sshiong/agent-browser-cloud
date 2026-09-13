package io.browsercloud.api;

import static io.browsercloud.api.ChallengeAutomationModels.*;

import io.browsercloud.application.ChallengeAutomationApplicationService;
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

/** Fixed least-privilege IPC surface for isolated screenshot OCR/vision workers. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ChallengeVisualWorkerController {

  private final ChallengeAutomationApplicationService service;
  private final WorkerQueueWakeupService wakeups;
  private final PlatformIdentity identity;

  public ChallengeVisualWorkerController(
      ChallengeAutomationApplicationService service,
      WorkerQueueWakeupService wakeups,
      PlatformIdentity identity) {
    this.service = service;
    this.wakeups = wakeups;
    this.identity = identity;
  }

  @PostMapping("/challenge-visual-jobs:claim")
  public CompletableFuture<ResponseEntity<ChallengeVisualJobClaimView>> claim(
      @Valid @RequestBody ClaimChallengeVisualJobRequest request,
      @RequestParam(defaultValue = "0") @Min(0) @Max(WorkerQueueWakeupService.MAX_WAIT_SECONDS)
          int waitSeconds) {
    var actorId = requireWorkerActor();
    return wakeups
        .claim(
            WorkerQueueWakeupService.CHALLENGE_VISUAL,
            waitSeconds,
            () -> service.claim(request, actorId),
            Optional::isPresent)
        .thenApply(
            claimed ->
                claimed
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build()));
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
