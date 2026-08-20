package io.browsercloud.api;

import static io.browsercloud.api.AgentBrowserActionModels.*;

import io.browsercloud.application.AgentBrowserActionApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public coarse action entrypoint shared by Web, Tauri and generated SDKs. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/agent-browser")
@Validated
@PreAuthorize(PlatformRoles.OPERATE)
public class AgentBrowserActionController {

  private final AgentBrowserActionApplicationService service;
  private final PlatformIdentity identity;

  public AgentBrowserActionController(
      AgentBrowserActionApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/execute-actions")
  public AgentTaskView execute(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 96) String idempotencyKey,
      @Valid @RequestBody ExecuteActionsRequest request) {
    return service.execute(sessionId, identity.current().tenantId(), idempotencyKey, request);
  }
}
