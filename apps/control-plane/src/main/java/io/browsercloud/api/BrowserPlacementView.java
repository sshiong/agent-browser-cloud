package io.browsercloud.api;

import io.browsercloud.domain.session.ResourceClass;
import java.time.Instant;
import java.util.List;

public record BrowserPlacementView(
    String sessionId,
    String tenantId,
    String nodeId,
    ResourceClass requestedResourceClass,
    ResourceClass effectiveResourceClass,
    List<String> extensionIds,
    int unknownExtensionCount,
    int cpuMillis,
    int memoryRequestMib,
    int memoryLimitMib,
    int pidLimit,
    int tabBudget,
    boolean requiresDesktop,
    boolean requiresGpu,
    boolean requiresNativeOs,
    boolean requiresIsolation,
    boolean requiresMedia,
    int mediaSlots,
    int mediaBitrateKbps,
    int placementScore,
    String state,
    List<String> reasonCodes,
    Instant reservedAt,
    Instant activatedAt,
    Instant releasedAt) {}
