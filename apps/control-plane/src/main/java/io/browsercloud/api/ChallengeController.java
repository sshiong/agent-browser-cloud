package io.browsercloud.api;

import static io.browsercloud.api.ChallengeModels.*;

import io.browsercloud.application.ChallengeInputApplicationService;
import io.browsercloud.application.HumanAssistApplicationService;
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
public class ChallengeController {

  private final HumanAssistApplicationService service;
  private final ChallengeInputApplicationService challengeInputs;
  private final PlatformIdentity identity;

  public ChallengeController(
      HumanAssistApplicationService service,
      ChallengeInputApplicationService challengeInputs,
      PlatformIdentity identity) {
    this.service = service;
    this.challengeInputs = challengeInputs;
    this.identity = identity;
  }

  @GetMapping("/sessions/{sessionId}/challenges")
  public ChallengeEventListResponse list(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return service.list(sessionId, identity.current().tenantId(), limit);
  }

  @GetMapping("/challenges/{eventId}")
  public ChallengeEventView get(
      @PathVariable @Pattern(regexp = "^chl_[a-zA-Z0-9]{20}$") String eventId) {
    return service.get(eventId, identity.current().tenantId());
  }

  @GetMapping("/challenges/{eventId}/preview")
  public ChallengePreviewView preview(
      @PathVariable @Pattern(regexp = "^chl_[a-zA-Z0-9]{20}$") String eventId) {
    var principal = identity.current();
    return service.preview(eventId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/challenges/{eventId}/assist-authorizations")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<HumanAssistView> authorize(
      @PathVariable @Pattern(regexp = "^chl_[a-zA-Z0-9]{20}$") String eventId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody AuthorizeHumanAssistRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            service.authorize(
                eventId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
                body));
  }

  @PostMapping("/challenges/{eventId}/input-responses")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<ChallengeInputResponseView> submitInputResponse(
      @PathVariable @Pattern(regexp = "^chl_[a-zA-Z0-9]{20}$") String eventId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody SubmitChallengeInputResponseRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            challengeInputs.submit(
                eventId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
                body));
  }
}
