package io.browsercloud.api;

import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagListResponse;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagRequest;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagView;
import io.browsercloud.application.WorkspaceTagApplicationService;
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
@RequestMapping("/api/v1/tags")
@Validated
public class WorkspaceTagController {

  private final WorkspaceTagApplicationService service;
  private final PlatformIdentity identity;

  public WorkspaceTagController(WorkspaceTagApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public WorkspaceTagListResponse list() {
    return service.list(identity.current().tenantId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(PlatformRoles.ADMIN)
  public WorkspaceTagView create(
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      @Valid @RequestBody WorkspaceTagRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.create(
        principal.tenantId(), principal.actorId(), idempotencyKey, requestId(request), body);
  }

  @PutMapping("/{tagId}")
  @PreAuthorize(PlatformRoles.ADMIN)
  public WorkspaceTagView update(
      @PathVariable @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String tagId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      @Valid @RequestBody WorkspaceTagRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.update(
        principal.tenantId(), principal.actorId(), tagId, idempotencyKey, requestId(request), body);
  }

  @DeleteMapping("/{tagId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(PlatformRoles.ADMIN)
  public void delete(
      @PathVariable @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String tagId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    service.delete(
        principal.tenantId(), principal.actorId(), tagId, idempotencyKey, requestId(request));
  }

  @PutMapping("/{tagId}/sessions/{sessionId}")
  @PreAuthorize(PlatformRoles.OPERATE)
  public WorkspaceTagView assign(
      @PathVariable @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String tagId,
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.assign(
        principal.tenantId(),
        principal.actorId(),
        tagId,
        sessionId,
        idempotencyKey,
        requestId(request));
  }

  @DeleteMapping("/{tagId}/sessions/{sessionId}")
  @PreAuthorize(PlatformRoles.OPERATE)
  public WorkspaceTagView unassign(
      @PathVariable @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String tagId,
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.unassign(
        principal.tenantId(),
        principal.actorId(),
        tagId,
        sessionId,
        idempotencyKey,
        requestId(request));
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
