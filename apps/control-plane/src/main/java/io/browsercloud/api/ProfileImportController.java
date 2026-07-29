package io.browsercloud.api;

import io.browsercloud.application.ProfileImportApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/profile-imports")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class ProfileImportController {

  private final ProfileImportApplicationService service;
  private final PlatformIdentity identity;

  public ProfileImportController(
      ProfileImportApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<ProfileImportModels.ProfileImportView> importCheckpoint(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @RequestParam @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String profileId,
      @RequestParam @NotBlank @Size(max = 128) String profileName,
      @RequestParam(required = false) @Size(max = 1024) String profileDescription,
      @RequestParam @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String runtimeBuildId,
      @RequestParam @Pattern(regexp = "^[0-9a-fA-F]{64}$") String archiveSha256,
      @RequestPart("archive") MultipartFile archive,
      HttpServletRequest servletRequest) {
    var principal = identity.current();
    return ResponseEntity.status(201)
        .body(
            service.importCheckpoint(
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                String.valueOf(
                    servletRequest.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
                profileId,
                profileName,
                profileDescription,
                runtimeBuildId,
                archiveSha256,
                archive));
  }

  @GetMapping
  public ProfileImportModels.ProfileImportListResponse list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
    var principal = identity.current();
    return service.list(principal.tenantId(), principal.actorId(), limit);
  }

  @GetMapping("/{importId}")
  public ProfileImportModels.ProfileImportView get(
      @PathVariable @Pattern(regexp = "^pim_[A-Za-z0-9]{16,32}$") String importId) {
    var principal = identity.current();
    return service.get(importId, principal.tenantId(), principal.actorId());
  }
}
