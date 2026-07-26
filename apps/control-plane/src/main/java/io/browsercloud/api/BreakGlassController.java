package io.browsercloud.api;

import io.browsercloud.application.BreakGlassApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/break-glass-requests")
@Validated
@PreAuthorize(PlatformRoles.SECURITY_ADMIN)
public class BreakGlassController {

  private final BreakGlassApplicationService service;
  private final PlatformIdentity identity;

  public BreakGlassController(BreakGlassApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  public ResponseEntity<BreakGlassRequestView> request(
      @Valid @RequestBody CreateBreakGlassRequest request) {
    var principal = identity.current();
    return ResponseEntity.status(201)
        .body(service.request(principal.tenantId(), principal.actorId(), request));
  }

  @GetMapping
  public BreakGlassRequestListResponse list() {
    return service.list(identity.current().tenantId());
  }

  @PostMapping("/{requestId}:approve")
  public BreakGlassRequestView approve(
      @PathVariable @Pattern(regexp = "^bgr_[a-zA-Z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return service.approve(requestId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/{requestId}:reject")
  public BreakGlassRequestView reject(
      @PathVariable @Pattern(regexp = "^bgr_[a-zA-Z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return service.reject(requestId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/{requestId}:revoke")
  public BreakGlassRequestView revoke(
      @PathVariable @Pattern(regexp = "^bgr_[a-zA-Z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return service.revoke(requestId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/{requestId}:review")
  public BreakGlassRequestView review(
      @PathVariable @Pattern(regexp = "^bgr_[a-zA-Z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return service.review(requestId, principal.tenantId(), principal.actorId());
  }
}
