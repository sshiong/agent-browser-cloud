package io.browsercloud.api;

import static io.browsercloud.api.AgentBrowserScreenshotModels.*;

import io.browsercloud.application.AgentBrowserScreenshotApplicationService;
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

/** Coarse browser.screenshot boundary shared by Web, Tauri and generated SDKs. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/agent-browser/screenshots")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class AgentBrowserScreenshotController {
  private final AgentBrowserScreenshotApplicationService service;
  private final PlatformIdentity identity;

  public AgentBrowserScreenshotController(
      AgentBrowserScreenshotApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<ScreenshotView> capture(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody CaptureScreenshotRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            service.capture(
                sessionId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
                body));
  }

  @GetMapping("/{screenshotId}")
  public ScreenshotView get(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^shot_[a-zA-Z0-9]{20}$") String screenshotId,
      @RequestParam(defaultValue = "0") @Min(0) @Max(30_000) int waitMs) {
    var principal = identity.current();
    return service.get(sessionId, screenshotId, principal.tenantId(), principal.actorId(), waitMs);
  }

  @PostMapping("/{screenshotId}:redeem")
  @PreAuthorize(PlatformRoles.OPERATE)
  public SessionEvidenceModels.RedeemEvidenceAccessResponse redeem(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^shot_[a-zA-Z0-9]{20}$") String screenshotId,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.redeem(
        sessionId,
        screenshotId,
        principal.tenantId(),
        principal.actorId(),
        String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)));
  }
}
