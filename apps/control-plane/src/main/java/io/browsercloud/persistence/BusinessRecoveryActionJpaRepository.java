package io.browsercloud.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRecoveryActionJpaRepository
    extends JpaRepository<BusinessRecoveryActionEntity, String> {

  Optional<BusinessRecoveryActionEntity> findFirstByMigrationIdOrderByAttemptNumberDesc(
      String migrationId);

  List<BusinessRecoveryActionEntity> findAllByMigrationIdOrderByAttemptNumberAsc(
      String migrationId);

  long countByMigrationId(String migrationId);
}
