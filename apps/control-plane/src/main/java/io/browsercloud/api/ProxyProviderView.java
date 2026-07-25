package io.browsercloud.api;

public record ProxyProviderView(
    String providerId,
    String type,
    String endpoint,
    String expectedExitIp,
    boolean directFallbackAllowed,
    String state) {}
