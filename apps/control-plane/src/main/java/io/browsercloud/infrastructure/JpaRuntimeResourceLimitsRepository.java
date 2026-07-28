package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.application.BrowserCapacityApplicationService.BrowserPlacementNotFoundException;
import io.browsercloud.coordinator.RuntimeResourceLimitsRepository;
import io.browsercloud.domain.capacity.RuntimeResourceLimits;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JpaRuntimeResourceLimitsRepository implements RuntimeResourceLimitsRepository {

  private final BrowserPlacementJpaRepository repository;
  private final ObjectMapper mapper;

  public JpaRuntimeResourceLimitsRepository(
      BrowserPlacementJpaRepository repository, ObjectMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
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
        readExtensionIds(placement.getExtensionIds()),
        placement.getExtensionCpuWeight(),
        placement.getMediaEncoderSlots(),
        placement.isBackgroundTabsFrozen(),
        placement.isNewTabsBlocked(),
        readExtensionIds(placement.getPausedExtensionIds()),
        placement.getSuccessTraceSamplePercent(),
        placement.isRequiresDesktop(),
        placement.isRequiresGpu(),
        placement.isRequiresNativeOs(),
        placement.isRequiresIsolation());
  }

  private java.util.List<String> readExtensionIds(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("Placement extension IDs are invalid", exception);
    }
  }
}
