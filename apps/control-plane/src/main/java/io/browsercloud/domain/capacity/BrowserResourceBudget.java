package io.browsercloud.domain.capacity;

import io.browsercloud.domain.session.ResourceClass;

/**
 * 可执行的 Browser Resource Class 预算。
 *
 * <p>request 用于 Node Admission，limit 会下发 Runtime 隔离层。L0 不启动 Browser；L4/L5 分别要求 GPU 与原生 OS
 * Node，不能静默降级隔离边界。
 */
public record BrowserResourceBudget(
    ResourceClass resourceClass,
    int cpuMillis,
    int memoryRequestMib,
    int memoryLimitMib,
    int pidLimit,
    int tabBudget,
    boolean desktopAllowed,
    boolean gpuRequired,
    boolean nativeOsRequired) {

  public static BrowserResourceBudget of(ResourceClass resourceClass) {
    return switch (resourceClass) {
      case L0 -> new BrowserResourceBudget(resourceClass, 0, 0, 0, 0, 0, false, false, false);
      case L1 ->
          new BrowserResourceBudget(resourceClass, 300, 512, 768, 128, 4, false, false, false);
      case L2 ->
          new BrowserResourceBudget(resourceClass, 600, 768, 1280, 192, 8, false, false, false);
      case L3 ->
          new BrowserResourceBudget(resourceClass, 1000, 1024, 2048, 256, 16, true, false, false);
      case L4 ->
          new BrowserResourceBudget(resourceClass, 1500, 2048, 4096, 512, 24, true, true, false);
      case L5 ->
          new BrowserResourceBudget(resourceClass, 2000, 4096, 8192, 512, 32, true, false, true);
    };
  }

  public static ResourceClass promote(ResourceClass current) {
    return switch (current) {
      case L0, L1 -> ResourceClass.L2;
      case L2 -> ResourceClass.L3;
      case L3 -> ResourceClass.L4;
      case L4 -> ResourceClass.L5;
      case L5 -> ResourceClass.L5;
    };
  }
}
