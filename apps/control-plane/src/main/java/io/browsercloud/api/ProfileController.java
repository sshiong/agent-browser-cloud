package io.browsercloud.api;

import io.browsercloud.application.ProfileApplicationService;
import io.browsercloud.application.ProfileExportGovernanceService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize(PlatformRoles.READ)
public class ProfileController {

  private final ProfileApplicationService service;
  private final ProfileExportGovernanceService exports;
  private final PlatformIdentity identity;

  public ProfileController(
      ProfileApplicationService service,
      ProfileExportGovernanceService exports,
      PlatformIdentity identity) {
    this.service = service;
    this.exports = exports;
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

  @PostMapping("/{profileId}/export-grants")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ResponseEntity<ProfileExportModels.ProfileExportGrantView> createExportGrant(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody ProfileExportModels.CreateProfileExportGrantRequest request,
      HttpServletRequest servletRequest) {
    var principal = identity.current();
    return ResponseEntity.status(201)
        .body(
            exports.createGrant(
                profileId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                requestId(servletRequest),
                request));
  }

  @PostMapping("/{profileId}/export-grants/{grantId}:redeem")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ProfileExportModels.RedeemProfileExportResponse redeemExportGrant(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId,
      @PathVariable @Pattern(regexp = "^pxg_[a-zA-Z0-9]{16,}$") String grantId,
      HttpServletRequest servletRequest) {
    var principal = identity.current();
    return exports.redeem(
        profileId, grantId, principal.tenantId(), principal.actorId(), requestId(servletRequest));
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
