package io.browsercloud.api;

import io.browsercloud.api.WorkspaceOverviewModels.WorkspaceOverviewResponse;
import io.browsercloud.application.WorkspaceOverviewApplicationService;
import io.browsercloud.application.WorkspaceOverviewEventStreamService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authenticated tenant overview and its payload-free resumable invalidation stream. */
@RestController
@RequestMapping("/api/v1/workspace-overview")
@PreAuthorize(PlatformRoles.READ)
public class WorkspaceOverviewController {
  private final WorkspaceOverviewApplicationService service;
  private final WorkspaceOverviewEventStreamService eventStream;
  private final PlatformIdentity identity;

  public WorkspaceOverviewController(
      WorkspaceOverviewApplicationService service,
      WorkspaceOverviewEventStreamService eventStream,
      PlatformIdentity identity) {
    this.service = service;
    this.eventStream = eventStream;
    this.identity = identity;
  }

  @GetMapping
  public WorkspaceOverviewResponse get() {
    var principal = identity.current();
    return service.get(principal.tenantId(), principal.roles().contains("PLATFORM_ADMIN"));
  }

  @GetMapping(value = "/event-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    var principal = identity.current();
    return eventStream.subscribe(
        principal.tenantId(), principal.roles().contains("PLATFORM_ADMIN"), lastEventId);
  }
}
