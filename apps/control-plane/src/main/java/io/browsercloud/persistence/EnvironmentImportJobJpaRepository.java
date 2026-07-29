package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnvironmentImportJobJpaRepository
    extends JpaRepository<EnvironmentImportJobEntity, String> {

  List<EnvironmentImportJobEntity> findTop20ByTenantIdAndOwnerActorIdOrderByCreatedAtDesc(
      String tenantId, String ownerActorId);

  Optional<EnvironmentImportJobEntity> findByImportIdAndTenantIdAndOwnerActorId(
      String importId, String tenantId, String ownerActorId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select job from EnvironmentImportJobEntity job
      where job.importId = :importId
        and job.tenantId = :tenantId
        and job.ownerActorId = :ownerActorId
      """)
  Optional<EnvironmentImportJobEntity> findOwnedForUpdate(
      @Param("importId") String importId,
      @Param("tenantId") String tenantId,
      @Param("ownerActorId") String ownerActorId);
}
