package io.browsercloud.api;

import io.browsercloud.application.ProfileApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
@Validated
public class ProfileController {

  private final ProfileApplicationService service;

  public ProfileController(ProfileApplicationService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ProfileView> create(
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @Valid @RequestBody CreateProfileRequest request) {
    return ResponseEntity.status(201).body(service.create(tenantId, request));
  }

  @GetMapping
  public ProfileListResponse list(
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId) {
    return service.list(tenantId);
  }

  @GetMapping("/{profileId}")
  public ProfileView get(
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId) {
    return service.get(tenantId, profileId);
  }
}
