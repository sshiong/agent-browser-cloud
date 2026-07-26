package io.browsercloud.api;

import io.browsercloud.application.RuntimeBuildApplicationService;
import io.browsercloud.security.PlatformRoles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime-builds")
@PreAuthorize(PlatformRoles.READ)
public class RuntimeBuildController {

  private final RuntimeBuildApplicationService service;

  public RuntimeBuildController(RuntimeBuildApplicationService service) {
    this.service = service;
  }

  @GetMapping
  public RuntimeBuildListResponse list() {
    return service.list();
  }
}
