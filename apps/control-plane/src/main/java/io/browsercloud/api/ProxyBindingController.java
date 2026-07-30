package io.browsercloud.api;

import io.browsercloud.api.ProxyBindingModels.ProxyBindingListResponse;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingRequest;
import io.browsercloud.api.ProxyBindingModels.ProxyBindingView;
import io.browsercloud.application.StaticProxyApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/proxy-bindings")
@Validated
public class ProxyBindingController {

  private final StaticProxyApplicationService service;
  private final PlatformIdentity identity;

  public ProxyBindingController(StaticProxyApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public ProxyBindingListResponse list() {
    return service.listBindings(identity.current().tenantId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(PlatformRoles.ADMIN)
  public ProxyBindingView create(
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      @Valid @RequestBody ProxyBindingRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.createBinding(
        principal.tenantId(), principal.actorId(), idempotencyKey, requestId(request), body);
  }

  @PutMapping("/{bindingProfileId}")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ProxyBindingView update(
      @PathVariable @Pattern(regexp = "^pbind_[a-zA-Z0-9]{16,32}$") String bindingProfileId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      @Valid @RequestBody ProxyBindingRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.updateBinding(
        principal.tenantId(),
        principal.actorId(),
        bindingProfileId,
        idempotencyKey,
        requestId(request),
        body);
  }

  @DeleteMapping("/{bindingProfileId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(PlatformRoles.ADMIN)
  public void delete(
      @PathVariable @Pattern(regexp = "^pbind_[a-zA-Z0-9]{16,32}$") String bindingProfileId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    service.deleteBinding(
        principal.tenantId(),
        principal.actorId(),
        bindingProfileId,
        idempotencyKey,
        requestId(request));
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
