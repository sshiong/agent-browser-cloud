package io.browsercloud.api;

import static io.browsercloud.api.SessionIdentityModels.*;

import io.browsercloud.application.SessionIdentityApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Locked Session identity and approval-backed change workflow. */
@RestController
@RequestMapping("/api/v1")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class SessionIdentityController {

  private final SessionIdentityApplicationService service;
  private final PlatformIdentity identity;

  public SessionIdentityController(
      SessionIdentityApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/sessions/{sessionId}/identity-spec")
  public SessionIdentitySpecView get(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.get(sessionId, identity.current().tenantId());
  }

  /** Explicit route so callers receive SESSION_CONFIG_LOCKED instead of silently mutating state. */
  @PutMapping("/sessions/{sessionId}/identity-spec")
  @PreAuthorize(PlatformRoles.OPERATE)
  public void rejectDirectMutation(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @Valid @RequestBody SessionIdentitySpecRequest ignored) {
    service.rejectDirectMutation(sessionId, identity.current().tenantId());
  }

  @PostMapping("/sessions/{sessionId}/identity-change-requests")
  @PreAuthorize(PlatformRoles.OPERATE)
  public SessionIdentityChangeRequestView requestChange(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CreateSessionIdentityChangeRequest request) {
    var principal = identity.current();
    return service.requestChange(
        sessionId, principal.tenantId(), principal.actorId(), idempotencyKey, request);
  }

  @PostMapping("/session-identity-change-requests/{requestId}:approve")
  @PreAuthorize(PlatformRoles.ADMIN)
  public SessionIdentityChangeRequestView approve(
      @PathVariable @Pattern(regexp = "^sicr_[A-Za-z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return service.decide(requestId, principal.tenantId(), principal.actorId(), true);
  }

  @PostMapping("/session-identity-change-requests/{requestId}:reject")
  @PreAuthorize(PlatformRoles.ADMIN)
  public SessionIdentityChangeRequestView reject(
      @PathVariable @Pattern(regexp = "^sicr_[A-Za-z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return service.decide(requestId, principal.tenantId(), principal.actorId(), false);
  }

  @PostMapping("/session-identity-change-requests/{requestId}:apply")
  @PreAuthorize(PlatformRoles.ADMIN)
  public SessionIdentityChangeRequestView apply(
      @PathVariable @Pattern(regexp = "^sicr_[A-Za-z0-9]{20}$") String requestId) {
    var principal = identity.current();
    return service.apply(requestId, principal.tenantId(), principal.actorId());
  }
}
