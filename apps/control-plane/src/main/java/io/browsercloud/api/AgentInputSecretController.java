package io.browsercloud.api;

import static io.browsercloud.api.AgentInputSecretModels.*;

import io.browsercloud.application.AgentInputSecretApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Write-only API for autonomous Agent username/password/OTP input. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/agent-input-secrets")
@Validated
@PreAuthorize(PlatformRoles.OPERATE)
public class AgentInputSecretController {

  private final AgentInputSecretApplicationService service;
  private final PlatformIdentity identity;

  public AgentInputSecretController(
      AgentInputSecretApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AgentInputSecretView create(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @Valid @RequestBody CreateAgentInputSecretRequest body,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$")
          String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.create(
        sessionId,
        principal.tenantId(),
        principal.actorId(),
        body,
        idempotencyKey,
        String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)));
  }
}
