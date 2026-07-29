package io.browsercloud.api;

import static io.browsercloud.api.BusinessRecoveryModels.*;

import io.browsercloud.application.ApplicationBusinessRecoveryService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Isolated trust boundary for Application Adapter Provider attestations. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/business-recovery/provider-evidence")
@Validated
public class BusinessRecoveryProviderEvidenceController {

  private final ApplicationBusinessRecoveryService service;
  private final PlatformIdentity identity;

  public BusinessRecoveryProviderEvidenceController(
      ApplicationBusinessRecoveryService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  @PreAuthorize(PlatformRoles.APPLICATION_ADAPTER)
  public ResponseEntity<ProviderEvidenceView> submit(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody SubmitProviderEvidenceRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.status(201)
        .body(
            service.submitProviderEvidence(
                sessionId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                requestId(request),
                body,
                Instant.now()));
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public ProviderEvidenceListResponse list(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.listProviderEvidence(sessionId, identity.current().tenantId());
  }

  private static String requestId(HttpServletRequest request) {
    var value = (String) request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE);
    return value == null ? "" : value;
  }
}
