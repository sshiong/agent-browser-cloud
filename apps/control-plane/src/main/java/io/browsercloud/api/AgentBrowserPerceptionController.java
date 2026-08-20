package io.browsercloud.api;

import static io.browsercloud.api.AgentBrowserPerceptionModels.*;

import io.browsercloud.application.AgentBrowserPerceptionService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Coarse, structured Browser API. Vision and screenshots are intentionally not the default path.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/agent-browser")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class AgentBrowserPerceptionController {

  private final AgentBrowserPerceptionService service;
  private final PlatformIdentity identity;

  public AgentBrowserPerceptionController(
      AgentBrowserPerceptionService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/snapshot")
  public SnapshotView snapshot(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.snapshot(sessionId, identity.current().tenantId());
  }

  @PostMapping("/inspect")
  public TargetListView inspect(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @Valid @RequestBody InspectRequest request) {
    return service.inspect(sessionId, identity.current().tenantId(), request);
  }

  @PostMapping("/find")
  public TargetListView find(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @Valid @RequestBody FindRequest request) {
    return service.find(sessionId, identity.current().tenantId(), request);
  }
}
