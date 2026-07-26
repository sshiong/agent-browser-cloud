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
    boolean desktop,
    boolean gpu,
    boolean nativeOs,
    boolean isolated) {

  public RuntimeResourceLimits {
    if (cpuMillis < 0
        || memoryRequestMib < 0
        || memoryLimitMib < memoryRequestMib
        || pidLimit < 0
        || tabBudget < 0) {
      throw new IllegalArgumentException("Runtime resource limits are invalid");
    }
  }
}
