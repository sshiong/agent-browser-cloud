package io.browsercloud.api;

import static io.browsercloud.api.EnvironmentSavedViewModels.*;

import io.browsercloud.application.EnvironmentSavedViewApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/environment-saved-views")
@Validated
public class EnvironmentSavedViewController {

  private final EnvironmentSavedViewApplicationService service;
  private final PlatformIdentity identity;

  public EnvironmentSavedViewController(
      EnvironmentSavedViewApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public EnvironmentSavedViewListResponse list() {
    var principal = identity.current();
    return service.list(principal.tenantId(), principal.actorId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(PlatformRoles.OPERATE)
  public EnvironmentSavedViewView create(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody CreateEnvironmentSavedViewRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.create(
        principal.tenantId(),
        principal.actorId(),
        principal.roles(),
        idempotencyKey,
        requestId(request),
        body);
  }

  @PutMapping("/{savedViewId}")
  @PreAuthorize(PlatformRoles.OPERATE)
  public EnvironmentSavedViewView update(
      @PathVariable @Pattern(regexp = "^svw_[a-zA-Z0-9]{16,32}$") String savedViewId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody UpdateEnvironmentSavedViewRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.update(
        principal.tenantId(),
        principal.actorId(),
        principal.roles(),
        savedViewId,
        idempotencyKey,
        requestId(request),
        body);
  }

  @DeleteMapping("/{savedViewId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(PlatformRoles.OPERATE)
  public void delete(
      @PathVariable @Pattern(regexp = "^svw_[a-zA-Z0-9]{16,32}$") String savedViewId,
      @RequestParam @Min(0) long expectedVersion,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    service.delete(
        principal.tenantId(),
        principal.actorId(),
        principal.roles(),
        savedViewId,
        expectedVersion,
        idempotencyKey,
        requestId(request));
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
