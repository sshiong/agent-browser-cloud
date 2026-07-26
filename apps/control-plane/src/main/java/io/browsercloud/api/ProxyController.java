package io.browsercloud.api;

import io.browsercloud.application.StaticProxyApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/proxies")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class ProxyController {

  private final StaticProxyApplicationService service;
  private final PlatformIdentity identity;

  public ProxyController(StaticProxyApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  public ProxyOverviewResponse overview() {
    return service.overview(identity.current().tenantId());
  }
}
