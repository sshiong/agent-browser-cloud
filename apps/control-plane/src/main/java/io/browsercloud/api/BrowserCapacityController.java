package io.browsercloud.api;

import io.browsercloud.application.BrowserCapacityApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Browser Node、Extension Weight、Placement 与压力 Admission 管理 API。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class BrowserCapacityController {

  private final BrowserCapacityApplicationService service;
  private final PlatformIdentity identity;

  public BrowserCapacityController(
      BrowserCapacityApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/browser-nodes")
  @PreAuthorize(PlatformRoles.ADMIN)
  public BrowserNodeListResponse listNodes() {
    return service.listNodes();
  }

  @PutMapping("/browser-nodes/{nodeId}")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public BrowserNodeView registerNode(
      @PathVariable @Pattern(regexp = "^node_[a-zA-Z0-9_-]{1,123}$") String nodeId,
      @Valid @RequestBody RegisterBrowserNodeRequest request) {
    return service.registerNode(nodeId, request, Instant.now());
  }

  @PostMapping("/browser-nodes/{nodeId}:pressure")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public BrowserNodeView pressure(
      @PathVariable @Pattern(regexp = "^node_[a-zA-Z0-9_-]{1,123}$") String nodeId,
      @Valid @RequestBody RecordNodePressureRequest request) {
    return service.recordPressure(nodeId, request, Instant.now());
  }

  @GetMapping("/extensions")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ExtensionProfileListResponse listExtensions() {
    return service.listExtensions();
  }

  @PutMapping("/extensions/{extensionId}")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public ExtensionProfileView upsertExtension(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String extensionId,
      @Valid @RequestBody UpsertExtensionProfileRequest request) {
    return service.upsertExtension(extensionId, request, Instant.now());
  }

  @GetMapping("/browser-placements/{sessionId}")
  @PreAuthorize(PlatformRoles.READ)
  public BrowserPlacementView getPlacement(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.getPlacement(sessionId, identity.current().tenantId());
  }
}
