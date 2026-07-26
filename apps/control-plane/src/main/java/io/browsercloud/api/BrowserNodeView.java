package io.browsercloud.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record BrowserNodeView(
    String nodeId,
    String region,
    String grpcTarget,
    String lifecycleState,
    String admissionState,
    int certifiedCpuMillis,
    int certifiedMemoryMib,
    int certifiedPidCount,
    int certifiedGpuSlots,
    int certifiedMediaSlots,
    int safetyMarginPercent,
    int reservedCpuMillis,
    int reservedMemoryMib,
    int reservedPidCount,
    int reservedGpuSlots,
    int reservedMediaSlots,
    int activeSessions,
    int maxSessions,
    BigDecimal memoryPsiSomeAvg10,
    BigDecimal memoryPsiFullAvg10,
    BigDecimal cpuPsiSomeAvg10,
    BigDecimal ioPsiFullAvg10,
    String pressureState,
    String pressureReason,
    boolean supportsDesktop,
    boolean supportsGpu,
    boolean supportsMedia,
    boolean supportsNativeOs,
    boolean isolationCapable,
    Map<String, String> labels,
    Instant lastHeartbeatAt,
    Instant updatedAt) {}
