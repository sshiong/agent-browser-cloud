package io.browsercloud.api;

import io.browsercloud.application.StaticProxyApplicationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/proxies")
@Validated
public class ProxyController {

  private final StaticProxyApplicationService service;

  public ProxyController(StaticProxyApplicationService service) {
    this.service = service;
  }

  @GetMapping
  public ProxyOverviewResponse overview(
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId) {
    return service.overview(tenantId);
  }
}
