package io.browsercloud.application;

import io.browsercloud.persistence.SessionMigrationJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionMigrationScheduler {

  private static final Logger log = LoggerFactory.getLogger(SessionMigrationScheduler.class);
  private static final Set<String> RECONCILABLE =
      Set.of(
          "CHECKPOINTING",
          "PLACING_TARGET",
          "RESTORING",
          "TARGET_CLEANUP",
          "STATE_RESYNC",
          "BUSINESS_VALIDATION",
          "BUSINESS_RECOVERY_ACTION");

  private final SessionMigrationJpaRepository migrations;
  private final SessionMigrationApplicationService service;

  public SessionMigrationScheduler(
      SessionMigrationJpaRepository migrations, SessionMigrationApplicationService service) {
    this.migrations = migrations;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${session-migration.reconcile-interval-ms:2000}")
  public void reconcile() {
    migrations
        .findAllByPhaseInOrderByUpdatedAtAsc(RECONCILABLE, PageRequest.of(0, 100))
        .forEach(
            migration -> {
              try {
                service.reconcile(migration.getMigrationId());
              } catch (SessionMigrationApplicationService.MigrationRejectedException exception) {
                log.warn(
                    "Session migration {} was rejected", migration.getMigrationId(), exception);
                service.fail(migration.getMigrationId(), exception.getMessage());
              } catch (RuntimeException exception) {
                log.warn(
                    "Session migration {} reconciliation will retry",
                    migration.getMigrationId(),
                    exception);
                if (Duration.between(migration.getUpdatedAt(), Instant.now()).toMinutes() >= 10) {
                  service.fail(migration.getMigrationId(), "MIGRATION_PHASE_TIMEOUT");
                }
              }
            });
  }
}
