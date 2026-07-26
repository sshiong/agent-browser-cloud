package io.browsercloud.api;

import io.browsercloud.application.AuditApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@Validated
@PreAuthorize(PlatformRoles.SECURITY_ADMIN)
public class AuditController {

  private final AuditApplicationService service;
  private final PlatformIdentity identity;

  public AuditController(AuditApplicationService service, PlatformIdentity identity) {
    this.service = service;
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
}
