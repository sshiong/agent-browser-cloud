package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.operation.OwnerType;
import io.browsercloud.persistence.SessionResourceAdjustmentEntity;
import io.browsercloud.persistence.SessionResourceAdjustmentJpaRepository;
import io.browsercloud.persistence.SessionResourceEventJpaRepository;
import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionResourceAdjustmentLifecycleServiceTest {
  private final SessionResourceAdjustmentJpaRepository adjustments =
      mock(SessionResourceAdjustmentJpaRepository.class);
  private final SessionResourcePolicyJpaRepository policies =
      mock(SessionResourcePolicyJpaRepository.class);
  private final SessionResourceEventJpaRepository events =
      mock(SessionResourceEventJpaRepository.class);
  private final OperationRepository operations = mock(OperationRepository.class);
  private SessionResourceAdjustmentLifecycleService service;

  @BeforeEach
  void setUp() {
    service =
        new SessionResourceAdjustmentLifecycleService(
            adjustments, policies, events, operations, new ObjectMapper());
  }

  @Test
  void createsRequestedLedgerWithBothResourceSnapshots() {
    service.requested(
        "op_resource00000001",
        "ses_resource0000001",
        "tenant-test",
        "SUSTAINED_CPU_PRESSURE",
        Map.of("cpuMillis", 600),
        Map.of("cpuMillis", 900),
        Instant.now());

    var captured = ArgumentCaptor.forClass(SessionResourceAdjustmentEntity.class);
    verify(adjustments).save(captured.capture());
    assertThat(captured.getValue().getState()).isEqualTo("REQUESTED");
    assertThat(captured.getValue().getOldResources()).contains("600");
    assertThat(captured.getValue().getRequestedResources()).contains("900");
  }

  @Test
  void firstDispatchMovesLedgerAndExclusiveOperationToExecuting() {
    var adjustment = adjustment();
    when(adjustments.findForUpdate("op_resource00000001")).thenReturn(Optional.of(adjustment));
    when(operations.findActive("ses_resource0000001"))
        .thenReturn(Optional.of(operation(OperationPhase.PREPARING)));

    service.executing("ses_resource0000001", "op_resource00000001");

    assertThat(adjustment.getState()).isEqualTo("EXECUTING");
    verify(operations)
        .transitionPhase("op_resource00000001", OperationPhase.PREPARING, OperationPhase.EXECUTING);
    verify(events).save(any());
  }

  @Test
  void deadLetterFailsLedgerAndReleasesOnlyTheMatchingResourceFence() {
    var adjustment = adjustment();
    adjustment.markExecuting(Instant.now());
    when(adjustments.findForUpdate("op_resource00000001")).thenReturn(Optional.of(adjustment));
    when(operations.findActive("ses_resource0000001"))
        .thenReturn(Optional.of(operation(OperationPhase.EXECUTING)));

    service.dispatchFailed("ses_resource0000001", "op_resource00000001", "STALE_ROUTE_EPOCH");

    assertThat(adjustment.getState()).isEqualTo("FAILED");
    assertThat(adjustment.getFailureCode()).isEqualTo("STALE_ROUTE_EPOCH");
    verify(operations)
        .transition("op_resource00000001", OperationState.ACTIVE, OperationState.ABORTED);
    verify(events).save(any());
  }

  @Test
  void rejectedAcknowledgementFailsLedgerAndReleasesTheMatchingResourceFence() {
    var adjustment = adjustment();
    adjustment.markExecuting(Instant.now());
    when(adjustments.findForUpdate("op_resource00000001")).thenReturn(Optional.of(adjustment));
    when(operations.findActive("ses_resource0000001"))
        .thenReturn(Optional.of(operation(OperationPhase.EXECUTING)));

    service.acknowledgementFailed(
        "ses_resource0000001", "op_resource00000001", "RESOURCE_ADJUSTMENT_ACK_MISMATCH");

    assertThat(adjustment.getState()).isEqualTo("FAILED");
    assertThat(adjustment.getFailureCode()).isEqualTo("RESOURCE_ADJUSTMENT_ACK_MISMATCH");
    verify(operations)
        .transition("op_resource00000001", OperationState.ACTIVE, OperationState.ABORTED);
    verify(events).save(any());
  }

  private static SessionResourceAdjustmentEntity adjustment() {
    return SessionResourceAdjustmentEntity.requested(
        "op_resource00000001",
        "ses_resource0000001",
        "tenant-test",
        "SUSTAINED_CPU_PRESSURE",
        "{\"cpuMillis\":600}",
        "{\"cpuMillis\":900}",
        Instant.now());
  }

  private static ExclusiveOperation operation(OperationPhase phase) {
    return new ExclusiveOperation(
        "op_resource00000001",
        "ses_resource0000001",
        OwnerType.SYSTEM,
        "resource-decision-engine",
        OperationMode.RESOURCE_ADJUSTMENT,
        20,
        1,
        1,
        1,
        null,
        false,
        false,
        phase,
        OperationState.ACTIVE,
        Set.of("resource.adjust"),
        Instant.now().plusSeconds(90),
        Instant.now(),
        null);
  }
}
