package io.browsercloud.api;

import io.browsercloud.application.KeyRotationApplicationService;
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
@RequestMapping("/api/v1/key-rotation-requests")
@Validated
@PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
public class KeyRotationController {

  private final KeyRotationApplicationService service;
  private final PlatformIdentity identity;

  public KeyRotationController(KeyRotationApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  public KeyRotationRequestView request(@Valid @RequestBody CreateKeyRotationRequest request) {
    var principal = identity.current();
    return service.request(principal.tenantId(), principal.actorId(), request);
  }

  @GetMapping
  public KeyRotationRequestListResponse list() {
    return service.list(identity.current().tenantId());
  }

  @PostMapping("/{rotationId}:approve")
  public KeyRotationRequestView approve(
      @PathVariable @Pattern(regexp = "^rot_[A-Za-z0-9]{20}$") String rotationId) {
    var principal = identity.current();
    return service.approve(rotationId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/{rotationId}:complete")
  public KeyRotationRequestView complete(
      @PathVariable @Pattern(regexp = "^rot_[A-Za-z0-9]{20}$") String rotationId,
      @Valid @RequestBody CompleteKeyRotationRequest request) {
    var principal = identity.current();
    return service.complete(rotationId, principal.tenantId(), principal.actorId(), request);
  }

  @PostMapping("/{rotationId}:revoke")
  public KeyRotationRequestView revoke(
      @PathVariable @Pattern(regexp = "^rot_[A-Za-z0-9]{20}$") String rotationId) {
    var principal = identity.current();
    return service.revoke(rotationId, principal.tenantId(), principal.actorId());
  }
}
