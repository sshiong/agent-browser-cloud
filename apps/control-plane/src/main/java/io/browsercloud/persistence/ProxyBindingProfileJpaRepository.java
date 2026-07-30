package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProxyBindingProfileJpaRepository
    extends JpaRepository<ProxyBindingProfileEntity, String> {

  List<ProxyBindingProfileEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

  Optional<ProxyBindingProfileEntity> findByBindingProfileIdAndTenantId(
      String bindingProfileId, String tenantId);
}
