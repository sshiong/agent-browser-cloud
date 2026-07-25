package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProxyAllocationJpaRepository extends JpaRepository<ProxyAllocationEntity, String> {

  Optional<ProxyAllocationEntity> findFirstBySessionIdAndStateIn(
      String sessionId, List<String> states);

  List<ProxyAllocationEntity> findAllByTenantIdOrderByAllocatedAtDesc(String tenantId);
}
