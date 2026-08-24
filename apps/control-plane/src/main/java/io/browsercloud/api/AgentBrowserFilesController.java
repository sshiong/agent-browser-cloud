package io.browsercloud.api;

import static io.browsercloud.api.AgentBrowserFilesModels.*;

import io.browsercloud.application.AgentBrowserFilesApplicationService;
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

/** Coarse browser.files read boundary shared by Web, Tauri and generated SDKs. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/agent-browser/files")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class AgentBrowserFilesController {
  private final AgentBrowserFilesApplicationService service;
  private final PlatformIdentity identity;

  public AgentBrowserFilesController(
      AgentBrowserFilesApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/downloads")
  public DownloadListView downloads(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.downloads(sessionId, identity.current().tenantId());
  }

  @GetMapping("/downloads/{downloadId}:wait")
  public DownloadView waitForDownload(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^dld_[0-9a-f]{20}$") String downloadId,
      @RequestParam(defaultValue = "30000") @Min(100) @Max(30_000) int timeoutMs) {
    return service.waitForDownload(sessionId, identity.current().tenantId(), downloadId, timeoutMs);
  }

  @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<FileUploadView> upload(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
      @RequestParam @NotBlank @Size(max = 128) String targetRef,
      @RequestParam @Min(1) long targetRevision,
      @RequestParam @Min(1) long baseStateVersion,
      @RequestParam @Pattern(regexp = "^[0-9a-f]{64}$") String baseContentHash,
      @RequestParam @NotBlank @Size(max = 255) String filename,
      @RequestParam(defaultValue = "application/octet-stream") @NotBlank @Size(max = 255)
          String mimeType,
      @RequestParam @Pattern(regexp = "^[0-9a-f]{64}$") String contentSha256,
      @RequestPart("file") MultipartFile file,
      HttpServletRequest servletRequest) {
    var principal = identity.current();
    return ResponseEntity.status(202)
        .body(
            service.upload(
                sessionId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                String.valueOf(
                    servletRequest.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
                targetRef,
                targetRevision,
                baseStateVersion,
                baseContentHash,
                filename,
                mimeType,
                contentSha256,
                file));
  }

  @GetMapping("/uploads/{uploadId}")
  public FileUploadView upload(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^afu_[a-zA-Z0-9]{20}$") String uploadId) {
    var principal = identity.current();
    var upload = service.upload(uploadId, principal.tenantId());
    if (!upload.sessionId().equals(sessionId)) {
      throw new AgentBrowserFilesApplicationService.AgentBrowserFilesException(
          "AGENT_FILE_UPLOAD_NOT_FOUND");
    }
    return upload;
  }
}
