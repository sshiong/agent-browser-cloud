package io.browsercloud.api;

import io.browsercloud.application.ProfileApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class ProfileController {

  private final ProfileApplicationService service;
  private final PlatformIdentity identity;

  public ProfileController(ProfileApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<ProfileView> create(@Valid @RequestBody CreateProfileRequest request) {
    return ResponseEntity.status(201).body(service.create(identity.current().tenantId(), request));
  }

  @GetMapping
  public ProfileListResponse list() {
    return service.list(identity.current().tenantId());
  }

  @GetMapping("/{profileId}")
  public ProfileView get(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId) {
    return service.get(identity.current().tenantId(), profileId);
  }
}
