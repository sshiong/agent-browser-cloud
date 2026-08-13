package io.browsercloud.api;

import io.browsercloud.application.AuditApplicationService;
import io.browsercloud.application.AuditEventStreamService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/audit-events")
@Validated
@PreAuthorize(PlatformRoles.SECURITY_ADMIN)
public class AuditController {

  private final AuditApplicationService service;
  private final AuditEventStreamService eventStream;
  private final PlatformIdentity identity;

  public AuditController(
      AuditApplicationService service,
      AuditEventStreamService eventStream,
      PlatformIdentity identity) {
    this.service = service;
    this.eventStream = eventStream;
    this.identity = identity;
  }

  @GetMapping
  public AuditEventListResponse list(
      @RequestParam(required = false) @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(required = false) @Size(max = 128) String eventType,
      @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return service.list(identity.current().tenantId(), sessionId, eventType, limit, offset);
  }

  @GetMapping(value = "/event-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    return eventStream.subscribe(identity.current().tenantId(), lastEventId);
  }
}
