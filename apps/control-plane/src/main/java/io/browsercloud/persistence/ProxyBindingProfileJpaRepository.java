package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProxyBindingProfileJpaRepository
    extends JpaRepository<ProxyBindingProfileEntity, String> {

  List<ProxyBindingProfileEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

  Optional<ProxyBindingProfileEntity> findByBindingProfileIdAndTenantId(
      String bindingProfileId, String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select profile from ProxyBindingProfileEntity profile "
          + "where profile.tenantId = :tenantId order by profile.bindingProfileId")
  List<ProxyBindingProfileEntity> findAllForAutomaticRouting(String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select profile from ProxyBindingProfileEntity profile "
          + "where profile.bindingProfileId = :bindingProfileId and profile.tenantId = :tenantId")
  Optional<ProxyBindingProfileEntity> findForAssignment(String bindingProfileId, String tenantId);
}
