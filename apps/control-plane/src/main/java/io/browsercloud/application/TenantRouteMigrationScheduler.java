package io.browsercloud.application;

import io.browsercloud.persistence.CoordinatorRouteMigrationJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TenantRouteMigrationScheduler {

  private static final Logger log = LoggerFactory.getLogger(TenantRouteMigrationScheduler.class);
  private final CoordinatorRouteMigrationJpaRepository migrations;
  private final TenantRouteApplicationService service;

  public TenantRouteMigrationScheduler(
      CoordinatorRouteMigrationJpaRepository migrations, TenantRouteApplicationService service) {
    this.migrations = migrations;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${coordinator.route-migration-interval-ms:2000}")
  public void reconcile() {
    migrations
        .findAllByStateOrderByUpdatedAtAsc("MIGRATING", PageRequest.of(0, 20))
        .forEach(
            migration -> {
              try {
                service.reconcile(migration.getMigrationId());
              } catch (RuntimeException exception) {
                log.warn(
                    "Tenant route migration {} reconciliation will retry",
                    migration.getMigrationId(),
                    exception);
              }
            });
  }
}
