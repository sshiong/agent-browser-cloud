package io.browsercloud.coordinator;

/** Resolves the immutable policy revision bound to one Session. */
public interface BrowserTransactionPolicyRepository {
  BrowserTransactionPolicy find(String sessionId, String tenantId);
}
