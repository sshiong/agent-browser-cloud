package io.browsercloud.api;

import io.browsercloud.api.WorkspaceGroupModels.WorkspaceGroupListResponse;
import io.browsercloud.api.WorkspaceGroupModels.WorkspaceGroupRequest;
import io.browsercloud.api.WorkspaceGroupModels.WorkspaceGroupView;
import io.browsercloud.application.WorkspaceGroupApplicationService;
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
@RequestMapping("/api/v1/groups")
@Validated
public class WorkspaceGroupController {

  private final WorkspaceGroupApplicationService service;
  private final PlatformIdentity identity;

  public WorkspaceGroupController(
      WorkspaceGroupApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize(PlatformRoles.READ)
  public WorkspaceGroupListResponse list() {
    return service.list(identity.current().tenantId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(PlatformRoles.ADMIN)
  public WorkspaceGroupView create(
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      @Valid @RequestBody WorkspaceGroupRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.create(
        principal.tenantId(),
        principal.actorId(),
        idempotencyKey,
        requestId(request),
        body,
        principal.roles().contains("PLATFORM_ADMIN"));
  }

  @PutMapping("/{groupId}")
  @PreAuthorize(PlatformRoles.ADMIN)
  public WorkspaceGroupView update(
      @PathVariable @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      @Valid @RequestBody WorkspaceGroupRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.update(
        principal.tenantId(),
        principal.actorId(),
        groupId,
        idempotencyKey,
        requestId(request),
        body,
        principal.roles().contains("PLATFORM_ADMIN"));
  }

  @DeleteMapping("/{groupId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(PlatformRoles.ADMIN)
  public void delete(
      @PathVariable @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    service.delete(
        principal.tenantId(), principal.actorId(), groupId, idempotencyKey, requestId(request));
  }

  @PutMapping("/{groupId}/sessions/{sessionId}")
  @PreAuthorize(PlatformRoles.OPERATE)
  public WorkspaceGroupView assign(
      @PathVariable @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.assign(
        principal.tenantId(),
        principal.actorId(),
        groupId,
        sessionId,
        idempotencyKey,
        requestId(request));
  }

  @DeleteMapping("/{groupId}/sessions/{sessionId}")
  @PreAuthorize(PlatformRoles.OPERATE)
  public WorkspaceGroupView unassign(
      @PathVariable @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
      HttpServletRequest request) {
    var principal = identity.current();
    return service.unassign(
        principal.tenantId(),
        principal.actorId(),
        groupId,
        sessionId,
        idempotencyKey,
        requestId(request));
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
