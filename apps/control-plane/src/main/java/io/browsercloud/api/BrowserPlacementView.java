package io.browsercloud.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.browsercloud.domain.capacity.ResourceTemplate;
import io.browsercloud.domain.session.ResourceClass;
import java.time.Instant;
import java.util.List;

public record BrowserPlacementView(
    String sessionId,
    String tenantId,
    String nodeId,
    @JsonIgnore ResourceClass requestedResourceClass,
    @JsonIgnore ResourceClass effectiveResourceClass,
    List<String> extensionIds,
    int unknownExtensionCount,
    int cpuMillis,
    int memoryRequestMib,
    int memoryLimitMib,
    int pidLimit,
    int tabBudget,
    int stateCollectorBudgetPercent,
    int remoteDesktopBitrateKbps,
    int extensionCpuWeight,
    boolean requiresDesktop,
    boolean requiresGpu,
    boolean requiresNativeOs,
    boolean requiresIsolation,
    boolean requiresMedia,
    int mediaSlots,
    int mediaEncoderSlots,
    boolean backgroundTabsFrozen,
    boolean newTabsBlocked,
    List<String> pausedExtensionIds,
    int successTraceSamplePercent,
    int observerFrameRateFps,
    boolean videoRecordingRequested,
    boolean videoRecordingEnabled,
    int mediaBitrateKbps,
    int placementScore,
    String state,
    List<String> reasonCodes,
    Instant reservedAt,
    Instant activatedAt,
    Instant releasedAt) {

  @JsonProperty("requestedTemplate")
  public String requestedTemplate() {
    return ResourceTemplate.from(requestedResourceClass).id();
  }

  @JsonProperty("resolvedTemplate")
  public String resolvedTemplate() {
    return ResourceTemplate.from(effectiveResourceClass).id();
  }
}
