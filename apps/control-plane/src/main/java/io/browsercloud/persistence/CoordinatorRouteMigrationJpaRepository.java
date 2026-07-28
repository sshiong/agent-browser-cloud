package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoordinatorRouteMigrationJpaRepository
    extends JpaRepository<CoordinatorRouteMigrationEntity, String> {

  Optional<CoordinatorRouteMigrationEntity> findFirstByTenantIdAndStateOrderByCreatedAtDesc(
      String tenantId, String state);

  Optional<CoordinatorRouteMigrationEntity> findFirstByTenantIdOrderByCreatedAtDesc(
      String tenantId);

  List<CoordinatorRouteMigrationEntity> findAllByStateOrderByUpdatedAtAsc(
      String state, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select migration from CoordinatorRouteMigrationEntity migration where migration.migrationId = :migrationId")
  Optional<CoordinatorRouteMigrationEntity> findForUpdate(@Param("migrationId") String migrationId);
}
