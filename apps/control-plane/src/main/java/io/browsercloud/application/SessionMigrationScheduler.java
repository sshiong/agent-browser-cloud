package io.browsercloud.application;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

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
  private final CoordinatorCommandRoutingService commandRouting;

  public SessionMigrationScheduler(
      SessionMigrationJpaRepository migrations, CoordinatorCommandRoutingService commandRouting) {
    this.migrations = migrations;
    this.commandRouting = commandRouting;
  }

  @Scheduled(fixedDelayString = "${session-migration.reconcile-interval-ms:2000}")
  public void reconcile() {
    var now = Instant.now();
    var reconcileBucket = now.getEpochSecond() / 2;
    migrations
        .findAllByPhaseInOrderByUpdatedAtAsc(RECONCILABLE, PageRequest.of(0, 100))
        .forEach(
            migration -> {
              try {
                commandRouting.enqueueAsync(
                    migration.getSessionId(),
                    MIGRATION_RECONCILE,
                    "migration-reconcile:" + migration.getMigrationId() + ":" + reconcileBucket,
                    new MigrationReconcile(migration.getMigrationId(), now),
                    Duration.ofMinutes(2));
              } catch (RuntimeException exception) {
                log.warn(
                    "Session migration {} routing will retry",
                    migration.getMigrationId(),
                    exception);
              }
            });
  }
}
