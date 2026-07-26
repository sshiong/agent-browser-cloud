package io.browsercloud.api;

import io.browsercloud.application.RuntimeReleaseApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime-release-requests")
@Validated
@PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
public class RuntimeReleaseController {

  private final RuntimeReleaseApplicationService service;
  private final PlatformIdentity identity;

  public RuntimeReleaseController(
      RuntimeReleaseApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  public RuntimeReleaseRequestListResponse list() {
    return service.list(identity.current().tenantId());
  }

  @PostMapping("/{releaseId}:approve")
  public RuntimeReleaseRequestView approve(
      @PathVariable @Pattern(regexp = "^rel_[A-Za-z0-9]{20}$") String releaseId) {
    var principal = identity.current();
    return service.approve(releaseId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/{releaseId}:reject")
  public RuntimeReleaseRequestView reject(
      @PathVariable @Pattern(regexp = "^rel_[A-Za-z0-9]{20}$") String releaseId) {
    var principal = identity.current();
    return service.reject(releaseId, principal.tenantId(), principal.actorId());
  }
}
