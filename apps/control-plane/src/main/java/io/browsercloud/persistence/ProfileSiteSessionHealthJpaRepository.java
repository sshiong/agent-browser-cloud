package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileSiteSessionHealthJpaRepository
    extends JpaRepository<ProfileSiteSessionHealthEntity, String> {

  Optional<ProfileSiteSessionHealthEntity> findByTenantIdAndProfileIdAndSiteOrigin(
      String tenantId, String profileId, String siteOrigin);

  List<ProfileSiteSessionHealthEntity> findAllByTenantIdAndProfileIdOrderByUpdatedAtDesc(
      String tenantId, String profileId);

  List<ProfileSiteSessionHealthEntity> findAllByTenantId(String tenantId);
}
