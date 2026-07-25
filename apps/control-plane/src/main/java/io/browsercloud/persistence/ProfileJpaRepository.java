package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileJpaRepository extends JpaRepository<ProfileEntity, String> {
  List<ProfileEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
