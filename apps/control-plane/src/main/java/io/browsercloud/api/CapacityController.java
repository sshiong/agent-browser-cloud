package io.browsercloud.api;

import io.browsercloud.application.CapacityAdmissionService;
import io.browsercloud.security.PlatformRoles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/capacity")
@PreAuthorize(PlatformRoles.ADMIN)
public class CapacityController {

  private final CapacityAdmissionService service;

  public CapacityController(CapacityAdmissionService service) {
    this.service = service;
  }

  @GetMapping
  public CapacityAdmissionService.Decision get() {
    return service.snapshot();
  }
}
