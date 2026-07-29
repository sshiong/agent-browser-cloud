package io.browsercloud.domain.capacity;

import io.browsercloud.domain.session.ResourceClass;
import java.util.List;

/** Placement 决策下发给 Runtime Supervisor 的不可放宽资源边界。 */
public record RuntimeResourceLimits(
    ResourceClass resourceClass,
    int cpuMillis,
    int memoryRequestMib,
    int memoryLimitMib,
    int pidLimit,
    int tabBudget,
    int stateCollectorBudgetPercent,
    int remoteDesktopBitrateKbps,
    List<String> extensionIds,
    int extensionCpuWeight,
    int mediaEncoderSlots,
    boolean freezeBackgroundTabs,
    boolean blockNewTabs,
    List<String> pausedExtensionIds,
    int successTraceSamplePercent,
    int observerFrameRateFps,
    boolean videoRecordingEnabled,
    boolean desktop,
    boolean gpu,
    boolean nativeOs,
    boolean isolated) {

  public RuntimeResourceLimits {
    extensionIds = extensionIds == null ? List.of() : List.copyOf(extensionIds);
    pausedExtensionIds =
        pausedExtensionIds == null
            ? List.of()
            : pausedExtensionIds.stream().distinct().sorted().toList();
    if (cpuMillis < 0
        || memoryRequestMib < 0
        || memoryLimitMib < memoryRequestMib
        || pidLimit < 0
        || tabBudget < 0
        || stateCollectorBudgetPercent < 10
        || stateCollectorBudgetPercent > 100
        || remoteDesktopBitrateKbps < 0
        || remoteDesktopBitrateKbps > 100_000
        || successTraceSamplePercent < 1
        || successTraceSamplePercent > 100
        || observerFrameRateFps < 0
        || observerFrameRateFps > 60
        || (desktop && remoteDesktopBitrateKbps < 250)
        || (!desktop && remoteDesktopBitrateKbps != 0)
        || (desktop && observerFrameRateFps < 1)
        || (!desktop && observerFrameRateFps != 0)) {
      throw new IllegalArgumentException("Runtime resource limits are invalid");
    }
    if (extensionCpuWeight < 1
        || extensionCpuWeight > 10_000
        || mediaEncoderSlots < 0
        || mediaEncoderSlots > 32
        || !extensionIds.containsAll(pausedExtensionIds)
        || extensionIds.stream()
            .anyMatch(
                id ->
                    id == null
                        || id.isBlank()
                        || id.length() > 128
                        || !id.matches("[A-Za-z0-9._-]+"))) {
      throw new IllegalArgumentException("Runtime extension resource limits are invalid");
    }
  }
}
