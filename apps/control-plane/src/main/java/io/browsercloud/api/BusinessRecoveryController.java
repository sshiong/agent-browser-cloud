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

/** Tenant application recovery contracts and Session Business Recovery Ready Gate API. */
@RestController
@RequestMapping("/api/v1")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class BusinessRecoveryController {

  private final ApplicationBusinessRecoveryService service;
  private final PlatformIdentity identity;

  public BusinessRecoveryController(
      ApplicationBusinessRecoveryService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/applications/recovery-contracts")
  public RecoveryContractListResponse listContracts() {
    return service.listContracts(identity.current().tenantId());
  }

  @GetMapping("/applications/{applicationId}/recovery-contract")
  public RecoveryContractView getContract(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId) {
    return service.getContract(identity.current().tenantId(), applicationId);
  }

  @PutMapping("/applications/{applicationId}/recovery-contract")
  @PreAuthorize(PlatformRoles.ADMIN)
  public RecoveryContractView upsertContract(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @Valid @RequestBody UpsertRecoveryContractRequest request) {
    return service.upsertContract(
        identity.current().tenantId(), applicationId, request, Instant.now());
  }

  @PostMapping("/sessions/{sessionId}/business-recovery:validate")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<BusinessRecoveryValidationView> validate(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    var requestId = (String) request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE);
    return ResponseEntity.accepted()
        .body(
            service.validateFromApi(
                sessionId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                requestId == null ? "" : requestId));
  }

  @GetMapping("/sessions/{sessionId}/business-recovery")
  public BusinessRecoveryValidationView latest(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.latest(sessionId, identity.current().tenantId());
  }
}
