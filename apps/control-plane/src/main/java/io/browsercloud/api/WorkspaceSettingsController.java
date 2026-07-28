package io.browsercloud.api;

import io.browsercloud.api.WorkspaceSettingsModels.WorkspaceSettingsRequest;
import io.browsercloud.api.WorkspaceSettingsModels.WorkspaceSettingsView;
import io.browsercloud.application.WorkspaceSettingsApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspace-settings")
@Validated
public class WorkspaceSettingsController {

  private final WorkspaceSettingsApplicationService service;
  private final PlatformIdentity identity;

  public WorkspaceSettingsController(
      WorkspaceSettingsApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public WorkspaceSettingsView get() {
    return service.get(identity.current().tenantId());
  }

  @PutMapping
  @PreAuthorize(PlatformRoles.ADMIN)
  public WorkspaceSettingsView update(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody WorkspaceSettingsRequest request,
      HttpServletRequest servletRequest) {
    var principal = identity.current();
    return service.update(
        principal.tenantId(),
        principal.actorId(),
        idempotencyKey,
        String.valueOf(servletRequest.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
        request);
  }
}
