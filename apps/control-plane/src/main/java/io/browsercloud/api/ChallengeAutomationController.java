package io.browsercloud.api;

import static io.browsercloud.api.ChallengeAutomationModels.*;

import io.browsercloud.application.ChallengeAutomationApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped operator policy and observable status for bounded visual Challenge automation. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/challenge-automation")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class ChallengeAutomationController {

  private final ChallengeAutomationApplicationService service;
  private final PlatformIdentity identity;

  public ChallengeAutomationController(
      ChallengeAutomationApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/policy")
  public ChallengeAutomationPolicyView policy(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.policy(sessionId, identity.current().tenantId());
  }

  @PutMapping("/policy")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ChallengeAutomationPolicyView updatePolicy(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @Valid @RequestBody UpdateChallengeAutomationPolicyRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.updatePolicy(
        sessionId,
        principal.tenantId(),
        principal.actorId(),
        body,
        String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)));
  }

  @GetMapping("/current")
  public ResponseEntity<ChallengeAutomationRunView> current(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service
        .currentRun(sessionId, identity.current().tenantId())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }
}
