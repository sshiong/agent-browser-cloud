package io.browsercloud.api;

import static io.browsercloud.api.AgentClipboardModels.*;

import io.browsercloud.application.AgentClipboardApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agent-only clipboard. The VNC UserClipboard is intentionally not exposed here. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/agent-browser/clipboard")
@Validated
@PreAuthorize(PlatformRoles.OPERATE)
public class AgentClipboardController {

  private final AgentClipboardApplicationService service;
  private final PlatformIdentity identity;

  public AgentClipboardController(
      AgentClipboardApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  public AgentClipboardView read(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(defaultValue = "true") boolean includeValue) {
    var principal = identity.current();
    return service.read(sessionId, principal.tenantId(), principal.actorId(), includeValue);
  }

  @PutMapping
  public AgentClipboardView write(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @Valid @RequestBody WriteAgentClipboardRequest request) {
    var principal = identity.current();
    return service.write(sessionId, principal.tenantId(), principal.actorId(), request);
  }

  @DeleteMapping
  public AgentClipboardView clear(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam @Min(0) long expectedVersion) {
    var principal = identity.current();
    return service.clear(sessionId, principal.tenantId(), principal.actorId(), expectedVersion);
  }
}
