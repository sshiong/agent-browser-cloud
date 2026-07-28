package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.EnterpriseOperationsModels.SessionCostExplanationView;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.api.SessionResourceModels.RecordResourceSampleRequest;
import io.browsercloud.application.EnterpriseOperationsApplicationService.EnterpriseResourceNotFoundException;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import io.browsercloud.domain.resource.ResourcePolicyStatus;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.BrowserPlacementEntity;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.ExtensionProfileEntity;
import io.browsercloud.persistence.ExtensionProfileJpaRepository;
import io.browsercloud.persistence.SessionResourceCostSnapshotJpaRepository;
import io.browsercloud.persistence.SessionResourceEventJpaRepository;
import io.browsercloud.persistence.SessionResourcePolicyEntity;
import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import io.browsercloud.persistence.SessionResourceSampleEntity;
import io.browsercloud.persistence.SessionResourceSampleJpaRepository;
import io.browsercloud.proto.node.v1.AdjustRuntimeResourcesCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionResourceCostAndMaximumPolicyTest {
  private final SessionResourcePolicyJpaRepository policies =
      mock(SessionResourcePolicyJpaRepository.class);
  private final SessionResourceSampleJpaRepository samples =
      mock(SessionResourceSampleJpaRepository.class);
  private final SessionResourceEventJpaRepository events =
      mock(SessionResourceEventJpaRepository.class);
  private final SessionResourceCostSnapshotJpaRepository costSnapshots =
      mock(SessionResourceCostSnapshotJpaRepository.class);
  private final BrowserPlacementJpaRepository placements =
      mock(BrowserPlacementJpaRepository.class);
  private final ExtensionProfileJpaRepository extensionProfiles =
      mock(ExtensionProfileJpaRepository.class);
  private final AgentTaskJpaRepository tasks = mock(AgentTaskJpaRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final OperationRepository operations = mock(OperationRepository.class);
  private final NodeCommandGateway nodeCommands = mock(NodeCommandGateway.class);
  private final EnterpriseOperationsApplicationService enterprise =
      mock(EnterpriseOperationsApplicationService.class);
  private SessionResourceApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new SessionResourceApplicationService(
            policies,
            samples,
            events,
            costSnapshots,
            placements,
            extensionProfiles,
            tasks,
            sessions,
            operations,
            mock(IdempotencyService.class),
            nodeCommands,
            mock(SafePointApplicationService.class),
            enterprise,
            new ObjectMapper());
  }

  @Test
  void persistedCostAboveMaximumPausesAgentButPreservesBrowser() {
    var policy = policy(MaximumReachedPolicy.PAUSE_AGENT, 0.05);
    when(policies.findById("ses_cost")).thenReturn(Optional.of(policy));
    when(enterprise.explainSessionCost("ses_cost", "tenant-test"))
        .thenReturn(cost(new BigDecimal("0.075000")));

    service.evaluateCostTrend("ses_cost");

    assertThat(policy.getCurrentHourlyCost()).isEqualByComparingTo("0.075000");
    assertThat(policy.getCostPricingVersion()).isEqualTo("pricing-v1");
    assertThat(policy.status()).isEqualTo(ResourcePolicyStatus.AGENT_PAUSED);
    verify(costSnapshots).save(any());
    verify(events, org.mockito.Mockito.atLeastOnce()).save(any());
  }

  @Test
  void configuredBudgetFailsClosedWhenAuthoritativeRateIsUnavailable() {
    var policy = policy(MaximumReachedPolicy.TERMINATE_STRICT, 0.05);
    when(policies.findById("ses_cost")).thenReturn(Optional.of(policy));
    when(enterprise.explainSessionCost(anyString(), anyString()))
        .thenThrow(new EnterpriseResourceNotFoundException("Cost Rate"));

    service.evaluateCostTrend("ses_cost");

    assertThat(policy.status()).isEqualTo(ResourcePolicyStatus.CRITICAL);
    assertThat(policy.getStatusReason()).isEqualTo("COST_EVALUATION_UNAVAILABLE_BROWSER_PRESERVED");
    assertThat(policy.getLastCostEvaluatedAt()).isNotNull();
  }

  @Test
  void strictTerminationCannotBeSelectedDuringCreateWithoutPlatformAdmin() {
    var now = Instant.now();

    assertThatThrownBy(
            () ->
                service.initialize(
                    session(now),
                    request(MaximumReachedPolicy.TERMINATE_STRICT, 0.05),
                    "tenant-operator",
                    "request-test",
                    false))
        .isInstanceOf(SessionResourceApplicationService.ResourcePolicyPermissionException.class);
  }

  @Test
  void nonAdminCannotLowerCostOfAnExistingStrictPolicyThroughPartialPatch() {
    var now = Instant.now();
    var policy = policy(MaximumReachedPolicy.TERMINATE_STRICT, 1.0);
    when(sessions.require("ses_cost")).thenReturn(session(now));
    when(policies.findBySessionIdAndTenantId("ses_cost", "tenant-test"))
        .thenReturn(Optional.of(policy));
    var partial =
        new ResourcePolicyRequest(
            ResourcePolicyMode.AUTO,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0.01,
            null,
            null,
            null);

    assertThatThrownBy(
            () ->
                service.update(
                    "ses_cost", "tenant-test", partial, "idem-test", "tenant-operator", false))
        .isInstanceOf(SessionResourceApplicationService.ResourcePolicyPermissionException.class);
  }

  @Test
  void maximumPressureDispatchesOneRealNonCoreMitigationBeforePause() throws Exception {
    var now = Instant.now();
    var policy = policy(MaximumReachedPolicy.PAUSE_AGENT, null);
    var placement = placement(now);
    when(policies.findById("ses_cost")).thenReturn(Optional.of(policy));
    when(samples.findBySessionIdAndObservedAtAfterOrderByObservedAtAsc(
            org.mockito.ArgumentMatchers.eq("ses_cost"), any()))
        .thenReturn(List.of(sample(now.minusSeconds(61), 95.0, 3_900), sample(now, 96.0, 3_950)));
    when(placements.findById("ses_cost")).thenReturn(Optional.of(placement));
    when(operations.findActive("ses_cost")).thenReturn(Optional.empty());
    when(operations.nextOperationEpoch("ses_cost")).thenReturn(7L);
    when(sessions.require("ses_cost")).thenReturn(session(now));
    var noncritical = mock(ExtensionProfileEntity.class);
    when(noncritical.getExtensionId()).thenReturn("extension.noncritical");
    when(noncritical.isPrivileged()).thenReturn(false);
    var privileged = mock(ExtensionProfileEntity.class);
    when(privileged.getExtensionId()).thenReturn("extension.privileged");
    when(privileged.isPrivileged()).thenReturn(true);
    when(extensionProfiles.findAllById(any())).thenReturn(List.of(noncritical, privileged));

    service.evaluatePolicy("ses_cost");

    assertThat(policy.status()).isEqualTo(ResourcePolicyStatus.SCALING_DOWN);
    assertThat(policy.getStatusReason())
        .isEqualTo("MAXIMUM_NON_CORE_MITIGATION_COMMAND_DISPATCHED");
    assertThat(policy.getMaximumMitigationOperationId()).startsWith("op_");
    verify(operations).insert(any());
    var command = ArgumentCaptor.forClass(io.browsercloud.coordinator.NodeCommand.class);
    verify(nodeCommands).send(command.capture());
    var payload = AdjustRuntimeResourcesCommand.parseFrom(command.getValue().payload());
    assertThat(payload.getFreezeBackgroundTabs()).isTrue();
    assertThat(payload.getBlockNewTabs()).isTrue();
    assertThat(payload.getExtensionIdsList())
        .containsExactly("extension.noncritical", "extension.privileged", "extension.unknown");
    assertThat(payload.getExtensionBackgroundPolicy().getPausedExtensionIdsList())
        .containsExactly("extension.noncritical");
  }

  private static SessionResourcePolicyEntity policy(
      MaximumReachedPolicy maximumReached, Double maximumCost) {
    return SessionResourcePolicyEntity.create(
        "ses_cost", "tenant-test", request(maximumReached, maximumCost), Instant.now());
  }

  private static ResourcePolicyRequest request(
      MaximumReachedPolicy maximumReached, Double maximumCost) {
    return new ResourcePolicyRequest(
        ResourcePolicyMode.AUTO,
        maximumReached,
        true,
        true,
        true,
        null,
        null,
        4_000,
        4_096,
        maximumCost,
        60,
        1_200,
        300);
  }

  private static BrowserPlacementEntity placement(Instant now) {
    var placement =
        new BrowserPlacementEntity(
            "ses_cost",
            "tenant-test",
            "node_test",
            ResourceClass.L4,
            ResourceClass.L4,
            "[\"extension.noncritical\",\"extension.privileged\",\"extension.unknown\"]",
            0,
            4_000,
            3_500,
            4_096,
            256,
            12,
            true,
            false,
            false,
            false,
            true,
            2,
            8_000,
            100,
            "[]",
            now);
    placement.activate(now);
    return placement;
  }

  private static SessionResourceSampleEntity sample(
      Instant observedAt, double cpuPercent, int memoryRssMib) {
    return new SessionResourceSampleEntity(
        "rs_" + observedAt.toEpochMilli(),
        "ses_cost",
        "tenant-test",
        new RecordResourceSampleRequest(
            "node_test",
            cpuPercent,
            memoryRssMib,
            6.0,
            12,
            4,
            1_500,
            2_000,
            150,
            60L * 1024 * 1024,
            75.0,
            600,
            1_500,
            90.0,
            "",
            observedAt),
        observedAt);
  }

  private static SessionContext session(Instant now) {
    return new SessionContext(
        "ses_cost",
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
        ResourceClass.L4,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }

  private static SessionCostExplanationView cost(BigDecimal total) {
    return new SessionCostExplanationView(
        "ses_cost",
        "node_test",
        "local",
        "L4",
        "pricing-v1",
        4_000,
        3_500,
        true,
        false,
        true,
        new BigDecimal("0.010000"),
        new BigDecimal("0.020000"),
        new BigDecimal("0.010000"),
        new BigDecimal("0.010000"),
        BigDecimal.ZERO,
        new BigDecimal("0.025000"),
        total,
        Instant.now());
  }
}
