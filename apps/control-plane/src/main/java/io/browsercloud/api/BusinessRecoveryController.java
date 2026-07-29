package io.browsercloud.api;

import static io.browsercloud.api.BusinessRecoveryModels.*;

import io.browsercloud.application.ApplicationBusinessRecoveryService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

  @GetMapping("/applications/{applicationId}/recovery-contract/revisions")
  public RecoveryContractRevisionListResponse listRevisions(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId) {
    return service.listRevisions(identity.current().tenantId(), applicationId);
  }

  @GetMapping("/applications/{applicationId}/recovery-contract/revisions/{version}/diff")
  public RecoveryContractDiffView diff(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @PathVariable @Min(1) long version,
      @RequestParam @Min(1) long compareToVersion) {
    return service.diff(identity.current().tenantId(), applicationId, version, compareToVersion);
  }

  @PutMapping("/applications/{applicationId}/recovery-contract")
  @PreAuthorize(PlatformRoles.ADMIN)
  public RecoveryContractView upsertContract(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @Valid @RequestBody UpsertRecoveryContractRequest request,
      HttpServletRequest httpRequest) {
    var principal = identity.current();
    return service.upsertContract(
        principal.tenantId(),
        applicationId,
        request,
        principal.actorId(),
        requestId(httpRequest),
        Instant.now());
  }

  @PostMapping("/applications/{applicationId}/recovery-contract:restore")
  @PreAuthorize(PlatformRoles.ADMIN)
  public RecoveryContractView restoreRevision(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody RestoreRecoveryContractRevisionRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.restoreRevision(
        principal.tenantId(),
        applicationId,
        body,
        principal.actorId(),
        idempotencyKey,
        requestId(request),
        Instant.now());
  }

  @PostMapping("/applications/{applicationId}/recovery-contract:request-approval")
  @PreAuthorize(PlatformRoles.ADMIN)
  public RecoveryContractApprovalView requestApproval(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @Valid @RequestBody RequestRecoveryContractApprovalRequest request,
      HttpServletRequest httpRequest) {
    var principal = identity.current();
    return service.requestApproval(
        principal.tenantId(),
        applicationId,
        request,
        principal.actorId(),
        requestId(httpRequest),
        Instant.now());
  }

  @PostMapping("/applications/{applicationId}/recovery-contract-approvals/{approvalId}:approve")
  @PreAuthorize(PlatformRoles.ADMIN)
  public RecoveryContractApprovalView approve(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @PathVariable @Pattern(regexp = "^ara_[A-Za-z0-9]{20}$") String approvalId,
      HttpServletRequest httpRequest) {
    var principal = identity.current();
    return service.approve(
        principal.tenantId(),
        applicationId,
        approvalId,
        principal.actorId(),
        requestId(httpRequest),
        Instant.now());
  }

  @PostMapping("/applications/{applicationId}/recovery-contract-approvals/{approvalId}:reject")
  @PreAuthorize(PlatformRoles.ADMIN)
  public RecoveryContractApprovalView reject(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
      @PathVariable @Pattern(regexp = "^ara_[A-Za-z0-9]{20}$") String approvalId,
      HttpServletRequest httpRequest) {
    var principal = identity.current();
    return service.reject(
        principal.tenantId(),
        applicationId,
        approvalId,
        principal.actorId(),
        requestId(httpRequest),
        Instant.now());
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
  public ResponseEntity<BusinessRecoveryValidationView> latest(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    try {
      return ResponseEntity.ok(service.latest(sessionId, identity.current().tenantId()));
    } catch (
        ApplicationBusinessRecoveryService.BusinessRecoveryValidationNotFoundException exception) {
      return ResponseEntity.noContent().build();
    }
  }

  @GetMapping("/sessions/{sessionId}/application-binding")
  public ResponseEntity<SessionApplicationBindingView> binding(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    try {
      return ResponseEntity.ok(service.binding(sessionId, identity.current().tenantId()));
    } catch (
        ApplicationBusinessRecoveryService.SessionApplicationBindingNotFoundException exception) {
      return ResponseEntity.noContent().build();
    }
  }

  @PostMapping("/sessions/{sessionId}/application-binding:rebind")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ResponseEntity<SessionApplicationRebindView> rebind(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody RebindSessionApplicationRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            service.rebind(
                sessionId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                requestId(request),
                body));
  }

  private static String requestId(HttpServletRequest request) {
    var value = (String) request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE);
    return value == null ? "" : value;
  }
}
