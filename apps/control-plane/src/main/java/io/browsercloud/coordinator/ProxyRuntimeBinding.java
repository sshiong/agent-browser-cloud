package io.browsercloud.coordinator;

/** Non-secret proxy allocation snapshot delivered to the isolated Network Helper. */
public record ProxyRuntimeBinding(
    String bindingId, String providerId, String expectedExitIp, String credentialRef) {}
