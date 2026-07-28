package io.browsercloud.api;

import static io.browsercloud.api.CoordinatorRouteModels.*;

import io.browsercloud.application.TenantRouteApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coordinator/tenant-route")
@Validated
public class CoordinatorRouteController {

  private final TenantRouteApplicationService service;
  private final PlatformIdentity identity;

  public CoordinatorRouteController(
      TenantRouteApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.ADMIN)
  public TenantRouteView get() {
    return service.get(identity.current().tenantId());
  }

  @GetMapping("/migration")
  @PreAuthorize(PlatformRoles.ADMIN)
  public TenantRouteMigrationView latestMigration() {
    return service
        .latestMigration(identity.current().tenantId())
        .orElseThrow(
            () ->
                new TenantRouteApplicationService.TenantRouteRejectedException(
                    "TENANT_ROUTE_MIGRATION_NOT_FOUND"));
  }

  @PostMapping("/migrations")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public TenantRouteMigrationView migrate(
      @RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{8,128}$")
          String idempotencyKey,
      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
      @Valid @RequestBody RequestTenantRouteMigrationRequest request) {
    var principal = identity.current();
    var effectiveRequestId = requestId == null || requestId.isBlank() ? idempotencyKey : requestId;
    return service.requestMigration(
        principal.tenantId(), principal.actorId(), idempotencyKey, effectiveRequestId, request);
  }
}
