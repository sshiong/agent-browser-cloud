package io.browsercloud.api;

import io.browsercloud.api.GlobalSearchModels.GlobalSearchResponse;
import io.browsercloud.api.GlobalSearchModels.SearchResourceType;
import io.browsercloud.application.GlobalSearchApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@PreAuthorize(PlatformRoles.READ)
@Validated
public class GlobalSearchController {

  private final GlobalSearchApplicationService service;
  private final PlatformIdentity identity;

  public GlobalSearchController(GlobalSearchApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping
  public GlobalSearchResponse search(
      @RequestParam("q") @Size(min = 2, max = 128) String query,
      @RequestParam(value = "types", required = false) Set<SearchResourceType> types,
      @RequestParam(value = "limit", defaultValue = "24") @Min(1) @Max(50) int limit) {
    var principal = identity.current();
    var canReadAdminResources =
        principal.roles().stream()
            .anyMatch(
                role -> Set.of("TENANT_ADMIN", "SECURITY_ADMIN", "PLATFORM_ADMIN").contains(role));
    return service.search(principal.tenantId(), query, types, limit, canReadAdminResources);
  }
}
