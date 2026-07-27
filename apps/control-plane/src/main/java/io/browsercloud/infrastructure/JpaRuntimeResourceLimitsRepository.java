package io.browsercloud.infrastructure;

import io.browsercloud.application.BrowserCapacityApplicationService.BrowserPlacementNotFoundException;
import io.browsercloud.coordinator.RuntimeResourceLimitsRepository;
import io.browsercloud.domain.capacity.RuntimeResourceLimits;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JpaRuntimeResourceLimitsRepository implements RuntimeResourceLimitsRepository {

  private final BrowserPlacementJpaRepository repository;

  public JpaRuntimeResourceLimitsRepository(BrowserPlacementJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public RuntimeResourceLimits require(String sessionId) {
    var placement =
        repository
            .findById(sessionId)
            .filter(entity -> !entity.getState().equals("RELEASED"))
            .orElseThrow(() -> new BrowserPlacementNotFoundException(sessionId));
    return new RuntimeResourceLimits(
        placement.effectiveResourceClass(),
        placement.getCpuMillis(),
        placement.getMemoryRequestMib(),
        placement.getMemoryLimitMib(),
        placement.getPidLimit(),
        placement.getTabBudget(),
        placement.getStateCollectorBudgetPercent(),
        placement.getRemoteDesktopBitrateKbps(),
        placement.isRequiresDesktop(),
        placement.isRequiresGpu(),
        placement.isRequiresNativeOs(),
        placement.isRequiresIsolation());
  }
}
