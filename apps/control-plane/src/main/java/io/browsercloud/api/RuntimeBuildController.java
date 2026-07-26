package io.browsercloud.api;

import io.browsercloud.application.RuntimeBuildApplicationService;
import io.browsercloud.application.RuntimeReleaseApplicationService;
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

@RestController
@RequestMapping("/api/v1/runtime-builds")
@PreAuthorize(PlatformRoles.READ)
@Validated
public class RuntimeBuildController {

  private final RuntimeBuildApplicationService service;
  private final RuntimeReleaseApplicationService releaseService;
  private final PlatformIdentity identity;

  public RuntimeBuildController(
      RuntimeBuildApplicationService service,
      RuntimeReleaseApplicationService releaseService,
      PlatformIdentity identity) {
    this.service = service;
    this.releaseService = releaseService;
    this.identity = identity;
  }

  @GetMapping
  public RuntimeBuildListResponse list() {
    return service.list();
  }

  @PostMapping("/{buildId}:promote")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public RuntimeReleaseRequestView promote(
      @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String buildId,
      @Valid @RequestBody CreateRuntimeReleaseRequest request) {
    var principal = identity.current();
    return releaseService.requestPromotion(
        principal.tenantId(),
        principal.actorId(),
        buildId,
        request.targetChannel(),
        request.reason());
  }

  @PostMapping("/{buildId}:disable")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public RuntimeReleaseRequestView disable(
      @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String buildId,
      @Valid @RequestBody CreateRuntimeDisableRequest request) {
    var principal = identity.current();
    return releaseService.requestDisable(
        principal.tenantId(), principal.actorId(), buildId, request.reason());
  }
}
