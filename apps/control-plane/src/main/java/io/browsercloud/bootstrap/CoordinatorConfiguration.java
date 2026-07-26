package io.browsercloud.bootstrap;

import io.browsercloud.coordinator.CoordinatorOwnershipService;
import io.browsercloud.coordinator.CoordinatorReconciliationMetrics;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.OutboxPublisher;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保持 Coordinator 本身不依赖 Spring，由启动层负责装配。 */
@Configuration
public class CoordinatorConfiguration {

  @Bean
  SessionCoordinator sessionCoordinator(
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      OutboxPublisher outboxPublisher,
      CoordinatorOwnershipService ownershipService,
      CoordinatorReconciliationMetrics reconciliationMetrics) {
    return new SessionCoordinator(
        sessionRepository,
        operationRepository,
        nodeCommandGateway,
        outboxPublisher,
        ownershipService,
        reconciliationMetrics);
  }

  @Bean
  CoordinatorReconciliationMetrics coordinatorReconciliationMetrics(MeterRegistry meterRegistry) {
    return new CoordinatorReconciliationMetrics(meterRegistry);
  }
}
