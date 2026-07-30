package io.browsercloud.infrastructure;

import io.browsercloud.coordinator.ProxyRuntimeBinding;
import io.browsercloud.coordinator.ProxyRuntimeBindingRepository;
import io.browsercloud.persistence.ProxyAllocationJpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaProxyRuntimeBindingRepository implements ProxyRuntimeBindingRepository {

  private final ProxyAllocationJpaRepository allocations;

  public JpaProxyRuntimeBindingRepository(ProxyAllocationJpaRepository allocations) {
    this.allocations = allocations;
  }

  @Override
  public Optional<ProxyRuntimeBinding> find(String sessionId, String bindingId) {
    if (bindingId == null || bindingId.isBlank()) return Optional.empty();
    return allocations
        .findById(bindingId)
        .filter(item -> sessionId.equals(item.getSessionId()))
        .filter(item -> !"RELEASED".equals(item.getState()))
        .map(
            allocation ->
                new ProxyRuntimeBinding(
                    allocation.getAllocationId(),
                    allocation.getProvider(),
                    allocation.getExpectedExitIp(),
                    allocation.getCredentialRef()));
  }
}
