package io.browsercloud.api;

import static io.browsercloud.api.WorkspaceNotificationModels.*;

import io.browsercloud.application.WorkspaceNotificationApplicationService;
import io.browsercloud.application.WorkspaceNotificationEventStreamService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize(PlatformRoles.READ)
@Validated
public class WorkspaceNotificationController {

  private final WorkspaceNotificationApplicationService service;
  private final WorkspaceNotificationEventStreamService eventStream;
  private final PlatformIdentity identity;

  public WorkspaceNotificationController(
      WorkspaceNotificationApplicationService service,
      WorkspaceNotificationEventStreamService eventStream,
      PlatformIdentity identity) {
    this.service = service;
    this.eventStream = eventStream;
    this.identity = identity;
  }

  @GetMapping
  public WorkspaceNotificationListResponse list(
      @RequestParam(defaultValue = "30") @Min(1) @Max(50) int limit,
      @RequestParam(required = false) @Min(1) Long beforeSequence) {
    var principal = identity.current();
    return service.list(principal.tenantId(), principal.actorId(), limit, beforeSequence);
  }

  @PatchMapping("/read-cursor")
  public WorkspaceNotificationReadState markRead(
      @Valid @RequestBody UpdateNotificationReadCursorRequest request) {
    var principal = identity.current();
    return service.markRead(
        principal.tenantId(), principal.actorId(), request.readThroughSequence());
  }

  @GetMapping(value = "/event-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    return eventStream.subscribe(identity.current().tenantId(), lastEventId);
  }
}
