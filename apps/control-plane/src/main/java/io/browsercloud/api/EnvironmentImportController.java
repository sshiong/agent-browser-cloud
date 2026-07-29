package io.browsercloud.api;

import static io.browsercloud.api.EnvironmentImportModels.*;

import io.browsercloud.application.EnvironmentImportApplicationService;
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
@RequestMapping("/api/v1")
@Validated
public class EnvironmentImportController {

  private final EnvironmentImportApplicationService service;
  private final PlatformIdentity identity;

  public EnvironmentImportController(
      EnvironmentImportApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/environment-imports")
  @PreAuthorize(PlatformRoles.OPERATE)
  public EnvironmentImportListResponse list() {
    var principal = identity.current();
    return service.list(principal.tenantId(), principal.actorId());
  }

  @GetMapping("/environment-imports/{importId}")
  @PreAuthorize(PlatformRoles.OPERATE)
  public EnvironmentImportView get(
      @PathVariable @Pattern(regexp = "^imp_[a-zA-Z0-9]{16,32}$") String importId) {
    var principal = identity.current();
    return service.get(principal.tenantId(), principal.actorId(), importId);
  }

  @PostMapping("/environment-imports:preview")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(PlatformRoles.OPERATE)
  public EnvironmentImportView preview(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody PreviewEnvironmentImportRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.preview(
        principal.tenantId(),
        principal.actorId(),
        principal.roles().contains("PLATFORM_ADMIN"),
        idempotencyKey,
        requestId(request),
        body);
  }

  @PostMapping("/environment-imports/{importId}:commit")
  @PreAuthorize(PlatformRoles.OPERATE)
  public EnvironmentImportView commit(
      @PathVariable @Pattern(regexp = "^imp_[a-zA-Z0-9]{16,32}$") String importId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CommitEnvironmentImportRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.commit(
        principal.tenantId(),
        principal.actorId(),
        principal.roles().contains("PLATFORM_ADMIN"),
        importId,
        idempotencyKey,
        requestId(request),
        body);
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
