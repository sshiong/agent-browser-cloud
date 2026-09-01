package io.browsercloud.api;

import static io.browsercloud.api.AgentClipboardBridgeModels.*;

import io.browsercloud.application.AgentClipboardBridgeApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit browser.clipboard bridge. It is never invoked by ordinary Agent actions. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/agent-browser/clipboard-bridges")
@Validated
@PreAuthorize(PlatformRoles.OPERATE)
public class AgentClipboardBridgeController {
  private final AgentClipboardBridgeApplicationService service;
  private final PlatformIdentity identity;

  public AgentClipboardBridgeController(
      AgentClipboardBridgeApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  public ClipboardBridgeView create(
      @PathVariable @Pattern(regexp = "^ses_[A-Za-z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{8,128}$")
          String idempotencyKey,
      @Valid @RequestBody CreateClipboardBridgeRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.create(
        sessionId,
        principal.tenantId(),
        principal.actorId(),
        idempotencyKey,
        requestId(request),
        body);
  }

  @PostMapping("/{bridgeId}:complete")
  public ClipboardBridgeView complete(
      @PathVariable @Pattern(regexp = "^ses_[A-Za-z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^acb_[A-Za-z0-9]{20}$") String bridgeId,
      @Valid @RequestBody CompleteClipboardBridgeRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.complete(
        sessionId, bridgeId, principal.tenantId(), principal.actorId(), requestId(request), body);
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
