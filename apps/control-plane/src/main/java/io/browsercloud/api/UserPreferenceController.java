package io.browsercloud.api;

import io.browsercloud.api.UserPreferenceModels.UpdateUserPreferencesRequest;
import io.browsercloud.api.UserPreferenceModels.UserPreferencesView;
import io.browsercloud.application.UserPreferenceApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user-preferences")
@PreAuthorize(PlatformRoles.READ)
public class UserPreferenceController {

  private final UserPreferenceApplicationService service;
  private final PlatformIdentity identity;

  public UserPreferenceController(
      UserPreferenceApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  public UserPreferencesView get() {
    var principal = identity.current();
    return service.get(principal.tenantId(), principal.actorId());
  }

  @PutMapping
  public UserPreferencesView update(
      @Valid @RequestBody UpdateUserPreferencesRequest request, HttpServletRequest servletRequest) {
    var principal = identity.current();
    return service.update(
        principal.tenantId(),
        principal.actorId(),
        String.valueOf(servletRequest.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
        request.themeMode());
  }
}
