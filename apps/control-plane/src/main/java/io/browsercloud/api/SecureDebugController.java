package io.browsercloud.api;

import io.browsercloud.application.SecureDebugApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@PreAuthorize(PlatformRoles.SECURITY_ADMIN)
public class SecureDebugController {

  private final SecureDebugApplicationService service;
  private final PlatformIdentity identity;

  public SecureDebugController(SecureDebugApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/break-glass-requests/{requestId}:start-secure-debug")
  public ResponseEntity<SecureDebugSessionView> start(
      @PathVariable @Pattern(regexp = "^bgr_[a-zA-Z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return ResponseEntity.status(201)
        .body(service.start(requestId, principal.tenantId(), principal.actorId()));
  }

  @GetMapping("/secure-debug-sessions")
  public SecureDebugSessionListResponse list() {
    return service.list(identity.current().tenantId());
  }

  @GetMapping("/secure-debug-sessions/{debugSessionId}/snapshot")
  public SecureDebugSnapshotView snapshot(
      @PathVariable @Pattern(regexp = "^dbg_[a-zA-Z0-9]{20}$") String debugSessionId) {
    var principal = identity.current();
    return service.snapshot(debugSessionId, principal.tenantId(), principal.actorId());
  }

  @PostMapping("/secure-debug-sessions/{debugSessionId}:end")
  public SecureDebugSessionView end(
      @PathVariable @Pattern(regexp = "^dbg_[a-zA-Z0-9]{20}$") String debugSessionId) {
    var principal = identity.current();
    return service.end(debugSessionId, principal.tenantId(), principal.actorId());
  }
}
