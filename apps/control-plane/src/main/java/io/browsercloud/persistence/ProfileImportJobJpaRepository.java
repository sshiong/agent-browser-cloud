package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileImportJobJpaRepository
    extends JpaRepository<ProfileImportJobEntity, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select job from ProfileImportJobEntity job
      where job.tenantId = :tenantId
        and job.ownerActorId = :actorId
        and job.idempotencyKey = :idempotencyKey
      """)
  Optional<ProfileImportJobEntity> findOwnedIdempotencyForUpdate(
      @Param("tenantId") String tenantId,
      @Param("actorId") String actorId,
      @Param("idempotencyKey") String idempotencyKey);

  Optional<ProfileImportJobEntity> findByImportIdAndTenantIdAndOwnerActorId(
      String importId, String tenantId, String ownerActorId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select job from ProfileImportJobEntity job where job.importId = :importId")
  Optional<ProfileImportJobEntity> findByIdForUpdate(@Param("importId") String importId);

  List<ProfileImportJobEntity> findAllByTenantIdAndOwnerActorIdOrderByCreatedAtDesc(
      String tenantId, String ownerActorId, Pageable pageable);

  long countByTenantIdAndOwnerActorId(String tenantId, String ownerActorId);
}
