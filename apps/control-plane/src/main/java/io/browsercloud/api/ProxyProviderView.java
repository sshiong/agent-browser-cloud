package io.browsercloud.api;

import java.math.BigDecimal;
import java.util.List;

public record ProxyProviderView(
    String providerId,
    String type,
    String endpoint,
    String expectedExitIp,
    boolean directFallbackAllowed,
    String state,
    List<String> regions,
    BigDecimal costPerGibUsd,
    int reputationScore,
    int maxConcurrentSessions) {}
