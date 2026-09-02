package io.browsercloud.api;

import static io.browsercloud.api.SessionDeletionModels.*;

import io.browsercloud.application.SessionDeletionApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Atomic soft deletion endpoint used by the shared Web/Tauri Environment list. */
@RestController
@RequestMapping("/api/v1/sessions:batch-delete")
@Validated
@PreAuthorize(PlatformRoles.OPERATE)
public class SessionDeletionController {

  private final SessionDeletionApplicationService service;
  private final PlatformIdentity identity;

  public SessionDeletionController(
      SessionDeletionApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping
  public BatchDeleteSessionsResponse delete(
      @Valid @RequestBody BatchDeleteSessionsRequest request,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      HttpServletRequest servletRequest) {
    var principal = identity.current();
    return service.delete(
        principal.tenantId(),
        principal.actorId(),
        request,
        idempotencyKey,
        String.valueOf(servletRequest.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)));
  }
}
