package io.browsercloud.domain.capacity;

import io.browsercloud.domain.session.ResourceClass;

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
    boolean desktop,
    boolean gpu,
    boolean nativeOs,
    boolean isolated) {

  public RuntimeResourceLimits {
    if (cpuMillis < 0
        || memoryRequestMib < 0
        || memoryLimitMib < memoryRequestMib
        || pidLimit < 0
        || tabBudget < 0
        || stateCollectorBudgetPercent < 10
        || stateCollectorBudgetPercent > 100
        || remoteDesktopBitrateKbps < 0
        || remoteDesktopBitrateKbps > 100_000
        || (desktop && remoteDesktopBitrateKbps < 250)
        || (!desktop && remoteDesktopBitrateKbps != 0)) {
      throw new IllegalArgumentException("Runtime resource limits are invalid");
    }
  }
}
