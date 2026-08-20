package io.browsercloud.coordinator;

/** Runtime-safe reader for the immutable Session Browser identity projection. */
@FunctionalInterface
public interface BrowserIdentitySpecRepository {
  BrowserIdentitySpec require(String sessionId, String tenantId);
}
