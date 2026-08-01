package io.browsercloud.api;

import static io.browsercloud.api.WorkspaceBatchOperationModels.*;

import io.browsercloud.application.WorkspaceBatchOperationApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspace-batch-operations")
@Validated
public class WorkspaceBatchOperationController {

  private final WorkspaceBatchOperationApplicationService service;
  private final PlatformIdentity identity;

  public WorkspaceBatchOperationController(
      WorkspaceBatchOperationApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize(PlatformRoles.OPERATE)
  public WorkspaceBatchOperationView create(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CreateWorkspaceBatchOperationRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.create(
        principal.tenantId(), principal.actorId(), idempotencyKey, requestId(request), body);
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public WorkspaceBatchOperationListResponse list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
    return service.list(identity.current().tenantId(), limit);
  }

  @GetMapping("/{batchOperationId}")
  @PreAuthorize(PlatformRoles.READ)
  public WorkspaceBatchOperationView get(
      @PathVariable @Pattern(regexp = "^bop_[a-zA-Z0-9]{16,32}$") String batchOperationId) {
    return service.get(identity.current().tenantId(), batchOperationId);
  }

  @PostMapping("/{batchOperationId}:cancel")
  @PreAuthorize(PlatformRoles.OPERATE)
  public WorkspaceBatchOperationView cancel(
      @PathVariable @Pattern(regexp = "^bop_[a-zA-Z0-9]{16,32}$") String batchOperationId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CancelWorkspaceBatchOperationRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.cancel(
        principal.tenantId(),
        principal.actorId(),
        batchOperationId,
        body.reason(),
        idempotencyKey,
        requestId(request));
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
