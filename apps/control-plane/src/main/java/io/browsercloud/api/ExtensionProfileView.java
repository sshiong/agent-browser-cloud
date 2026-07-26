package io.browsercloud.api;

import java.math.BigDecimal;
import java.time.Instant;

public record ExtensionProfileView(
    String extensionId,
    String displayName,
    int staticCpuWeight,
    int staticMemoryWeight,
    int startupWeight,
    int pageInjectionWeight,
    int serviceWorkerWeight,
    int cryptoWeight,
    int networkWeight,
    BigDecimal observedMultiplier,
    BigDecimal confidence,
    String profileState,
    boolean web3,
    boolean serviceWorker,
    boolean crypto,
    boolean privileged,
    long samples,
    Integer p95CpuMillis,
    Integer p95MemoryMib,
    Instant lastProfiledAt,
    String samplingTier,
    int samplingCpuBudgetMillis,
    Instant nextSampleAt,
    Instant updatedAt) {}
