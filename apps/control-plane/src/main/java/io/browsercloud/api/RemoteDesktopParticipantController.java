package io.browsercloud.api;

import static io.browsercloud.api.RemoteDesktopParticipantHistoryModels.*;
import static io.browsercloud.api.RemoteDesktopParticipantModels.*;

import io.browsercloud.application.RemoteDesktopParticipantApplicationService;
import io.browsercloud.application.RemoteDesktopParticipantHistoryApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/desktop-participants")
@Validated
public class RemoteDesktopParticipantController {
  private final RemoteDesktopParticipantApplicationService service;
  private final RemoteDesktopParticipantHistoryApplicationService history;
  private final PlatformIdentity identity;

  public RemoteDesktopParticipantController(
      RemoteDesktopParticipantApplicationService service,
      RemoteDesktopParticipantHistoryApplicationService history,
      PlatformIdentity identity) {
    this.service = service;
    this.history = history;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public RemoteDesktopParticipantListResponse list(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.list(sessionId, identity.current().tenantId());
  }

  @GetMapping("/history")
  @PreAuthorize(PlatformRoles.READ)
  public RemoteDesktopParticipantHistoryPage history(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
      @RequestParam(required = false) @Size(max = 512) String cursor) {
    return history.list(sessionId, identity.current().tenantId(), limit, cursor);
  }

  @PostMapping("/{connectionId}:revoke")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ResponseEntity<RemoteDesktopParticipantView> revoke(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^rdc_[a-zA-Z0-9]{20}$") String connectionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            service.revoke(
                sessionId,
                connectionId,
                principal.tenantId(),
                principal.actorId(),
                String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
                idempotencyKey));
  }
}
