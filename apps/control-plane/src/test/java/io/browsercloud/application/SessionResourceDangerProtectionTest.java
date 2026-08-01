package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.OperationResponse;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.api.SessionResourceModels.RecordResourceSampleRequest;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import io.browsercloud.domain.resource.ResourcePolicyStatus;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.BrowserPlacementEntity;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.ExtensionProfileJpaRepository;
import io.browsercloud.persistence.SessionResourceCostSnapshotJpaRepository;
import io.browsercloud.persistence.SessionResourceEventJpaRepository;
import io.browsercloud.persistence.SessionResourcePolicyEntity;
import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import io.browsercloud.persistence.SessionResourceSampleJpaRepository;
import io.browsercloud.proto.node.v1.AdjustRuntimeResourcesCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionResourceDangerProtectionTest {
  private final SessionResourcePolicyJpaRepository policies =
      mock(SessionResourcePolicyJpaRepository.class);
  private final SessionResourceSampleJpaRepository samples =
      mock(SessionResourceSampleJpaRepository.class);
  private final SessionResourceEventJpaRepository events =
      mock(SessionResourceEventJpaRepository.class);
  private final SessionResourceCostSnapshotJpaRepository costs =
      mock(SessionResourceCostSnapshotJpaRepository.class);
  private final BrowserPlacementJpaRepository placements =
      mock(BrowserPlacementJpaRepository.class);
  private final AgentTaskJpaRepository tasks = mock(AgentTaskJpaRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final OperationRepository operations = mock(OperationRepository.class);
  private final NodeCommandGateway nodeCommands = mock(NodeCommandGateway.class);
  private SessionResourceApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new SessionResourceApplicationService(
            policies,
            samples,
            events,
            costs,
            placements,
            mock(ExtensionProfileJpaRepository.class),
            tasks,
            sessions,
            operations,
            mock(IdempotencyService.class),
            nodeCommands,
            mock(SafePointApplicationService.class),
            mock(EnterpriseOperationsApplicationService.class),
            new ObjectMapper());
  }

  @Test
  void oomImmediatelyPausesAgentAndDispatchesDurableMemoryExpansion() throws Exception {
    var now = Instant.now();
    var policy = policy(now);
    var placement = placement(now);
    var task = mock(AgentTaskEntity.class);
    when(placements.findById("ses_danger")).thenReturn(Optional.of(placement));
    when(sessions.require("ses_danger")).thenReturn(session(now));
    when(policies.findBySessionIdAndTenantId("ses_danger", "tenant-test"))
        .thenReturn(Optional.of(policy));
    when(tasks.findAllBySessionIdAndState("ses_danger", "RUNNING")).thenReturn(List.of(task));
    when(operations.findActive("ses_danger")).thenReturn(Optional.empty());
    when(operations.nextOperationEpoch("ses_danger")).thenReturn(9L);
    when(samples.findBySessionIdOrderByObservedAtDesc(any(), any())).thenReturn(List.of());
    when(costs.findBySessionIdOrderByObservedAtDesc(any(), any())).thenReturn(List.of());

    service.recordSample("ses_danger", sample(now, "OOM"));

    verify(task).pauseByResourcePolicy(any());
    assertThat(policy.status()).isEqualTo(ResourcePolicyStatus.SCALING_UP);
    assertThat(policy.getStatusReason())
        .isEqualTo("DANGER_OOM_EMERGENCY_SCALE_UP_COMMAND_DISPATCHED");
    verify(operations).insert(any());
    var command = ArgumentCaptor.forClass(NodeCommand.class);
    verify(nodeCommands).send(command.capture());
    var payload = AdjustRuntimeResourcesCommand.parseFrom(command.getValue().payload());
    assertThat(payload.getMemoryLimitMib()).isGreaterThan(1_280);
    assertThat(payload.getMemoryLimitMib()).isLessThanOrEqualTo(4_096);
  }

  @Test
  void isolationFailurePersistsTerminationIntentForRetryableDispatcher() {
    var now = Instant.now();
    var policy = policy(now);
    when(sessions.require("ses_danger")).thenReturn(session(now));
    when(policies.findBySessionIdAndTenantId("ses_danger", "tenant-test"))
        .thenReturn(Optional.of(policy));
    when(tasks.findAllBySessionIdAndState("ses_danger", "RUNNING")).thenReturn(List.of());

    service.protectRuntimeCrash(
        "ses_danger", "tenant-test", "SECURITY_ISOLATION_FAILURE", "NODE_SECURITY_MONITOR");

    assertThat(policy.status()).isEqualTo(ResourcePolicyStatus.CRITICAL);
    assertThat(policy.getStatusReason())
        .isEqualTo("DANGER_SECURITY_ISOLATION_TERMINATION_REQUIRED");
    verify(events).save(any());
  }

  @Test
  void dispatcherUsesNormalTerminateOperationForPersistedDangerIntent() {
    var now = Instant.now();
    var policy = policy(now);
    policy.evaluate(
        ResourcePolicyStatus.CRITICAL, "DANGER_SECURITY_ISOLATION_TERMINATION_REQUIRED", now);
    var sessionService = mock(SessionApplicationService.class);
    var resourceService = mock(SessionResourceApplicationService.class);
    when(policies.findById("ses_danger")).thenReturn(Optional.of(policy));
    when(sessionService.terminateForDangerProtection(
            "ses_danger", "tenant-test", "SECURITY_ISOLATION_FAILURE"))
        .thenReturn(new OperationResponse("op_danger", OperationState.ACTIVE));
    var executor =
        new SessionResourceDecisionExecutor(
            policies,
            resourceService,
            sessionService,
            mock(SessionMigrationApplicationService.class));

    assertThat(executor.dispatchPending("ses_danger")).isTrue();

    verify(sessionService)
        .terminateForDangerProtection("ses_danger", "tenant-test", "SECURITY_ISOLATION_FAILURE");
    verify(resourceService)
        .dangerActionDispatched(
            "ses_danger", "SECURITY_ISOLATION_FAILURE", "op_danger", "PENDING_NODE_STOP");
  }

  private static SessionResourcePolicyEntity policy(Instant now) {
    return SessionResourcePolicyEntity.create(
        "ses_danger",
        "tenant-test",
        new ResourcePolicyRequest(
            ResourcePolicyMode.AUTO,
            MaximumReachedPolicy.PAUSE_AGENT,
            true,
            true,
            true,
            null,
            null,
            4_000,
            4_096,
            null,
            60,
            1_200,
            300),
        now);
  }

  private static BrowserPlacementEntity placement(Instant now) {
    var placement =
        new BrowserPlacementEntity(
            "ses_danger",
            "tenant-test",
            "node_test",
            ResourceClass.L2,
            ResourceClass.L2,
            "[]",
            0,
            1_000,
            768,
            1_280,
            128,
            4,
            false,
            false,
            false,
            false,
            false,
            0,
            0,
            100,
            "[]",
            now);
    placement.activate(now);
    return placement;
  }

  private static SessionContext session(Instant now) {
    return new SessionContext(
        "ses_danger",
        "tenant-test",
        "profile-test",
        "node_test",
        "runtime-stable",
        "iso-standard",
        "proxy-test",
        3,
        4,
        2,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }

  private static RecordResourceSampleRequest sample(Instant now, String dangerEvent) {
    return new RecordResourceSampleRequest(
        "node_test", 20.0, 1_200, 1.0, 4, 2, 100, 100, 0, 0L, 0.0, 0, null, null, dangerEvent, now);
  }
}
