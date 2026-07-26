package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuntimeReleaseRequestJpaRepository
    extends JpaRepository<RuntimeReleaseRequestEntity, String> {

  List<RuntimeReleaseRequestEntity> findAllByTenantIdOrderByRequestedAtDesc(String tenantId);

  boolean existsByBuildIdAndTargetChannelAndState(
      String buildId, String targetChannel, String state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select request from RuntimeReleaseRequestEntity request "
          + "where request.releaseId = :releaseId and request.tenantId = :tenantId")
  Optional<RuntimeReleaseRequestEntity> findForUpdate(
      @Param("releaseId") String releaseId, @Param("tenantId") String tenantId);
}
