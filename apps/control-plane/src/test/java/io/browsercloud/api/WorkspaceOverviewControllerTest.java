package io.browsercloud.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceOverviewControllerTest {

  @Test
  void exposesBrowserNodeInvalidationsOnlyToRolesThatCanOpenTheNodeInventory() {
    assertThat(WorkspaceOverviewController.canViewBrowserNodes(Set.of("TENANT_ADMIN"))).isTrue();
    assertThat(WorkspaceOverviewController.canViewBrowserNodes(Set.of("SECURITY_ADMIN"))).isTrue();
    assertThat(WorkspaceOverviewController.canViewBrowserNodes(Set.of("PLATFORM_ADMIN"))).isTrue();
    assertThat(WorkspaceOverviewController.canViewBrowserNodes(Set.of("TENANT_OPERATOR")))
        .isFalse();
    assertThat(WorkspaceOverviewController.canViewBrowserNodes(Set.of("TENANT_VIEWER"))).isFalse();
  }
}
