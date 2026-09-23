package io.browsercloud.api;

import static io.browsercloud.api.SessionEnvironmentModels.*;

import io.browsercloud.application.SessionEnvironmentApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sessions")
@Validated
@PreAuthorize(PlatformRoles.OPERATE)
public class SessionEnvironmentController {
  private final SessionEnvironmentApplicationService service;
  private final PlatformIdentity identity;

  public SessionEnvironmentController(
      SessionEnvironmentApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/{sessionId}:export-configuration")
  public EnvironmentConfigurationExportView exportConfiguration(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.exportConfiguration(
        sessionId, principal.tenantId(), principal.actorId(), requestId(request));
  }

  @PostMapping("/{sessionId}:clone")
  @ResponseStatus(HttpStatus.CREATED)
  public CloneEnvironmentResponse cloneConfiguration(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CloneEnvironmentRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.cloneConfiguration(
        sessionId,
        principal.tenantId(),
        principal.actorId(),
        principal.roles().contains("PLATFORM_ADMIN"),
        idempotencyKey,
        requestId(request),
        body);
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
