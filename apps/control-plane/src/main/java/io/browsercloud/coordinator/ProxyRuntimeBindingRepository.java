package io.browsercloud.coordinator;

import java.util.Optional;

/** Resolves the committed per-Session allocation without exposing credential material. */
public interface ProxyRuntimeBindingRepository {
  Optional<ProxyRuntimeBinding> find(String sessionId, String bindingId);
}
