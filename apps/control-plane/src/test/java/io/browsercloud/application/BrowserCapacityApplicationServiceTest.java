package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.RecordExtensionSampleRequest;
import io.browsercloud.application.BrowserCapacityApplicationService.BrowserCapacityUnavailableException;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.BrowserPlacementEntity;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.ExtensionProfileEntity;
import io.browsercloud.persistence.ExtensionProfileJpaRepository;
import io.browsercloud.persistence.ExtensionProfileSampleJpaRepository;
import io.browsercloud.persistence.SessionResourceDemandEntity;
import io.browsercloud.persistence.SessionResourceDemandJpaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrowserCapacityApplicationServiceTest {

  @Mock private BrowserNodeJpaRepository nodeRepository;
  @Mock private ExtensionProfileJpaRepository extensionRepository;
  @Mock private ExtensionProfileSampleJpaRepository extensionSampleRepository;
  @Mock private SessionResourceDemandJpaRepository demandRepository;
  @Mock private BrowserPlacementJpaRepository placementRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private EnterpriseOperationsApplicationService enterpriseOperationsService;
  @Mock private SessionResourceApplicationService sessionResourceService;

  private BrowserCapacityApplicationService service;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    service =
        new BrowserCapacityApplicationService(
            nodeRepository,
            extensionRepository,
            extensionSampleRepository,
            demandRepository,
            placementRepository,
            sessionRepository,
            enterpriseOperationsService,
            sessionResourceService,
            objectMapper);
  }

  @Test
  void unknownExtensionUsesProbationAndPromotesL1BeforePlacement() throws Exception {
    var now = Instant.now();
    var demand =
        new SessionResourceDemandEntity(
            "ses_1234567890abcdef",
            "tenant-a",
            ResourceClass.L1,
            2,
            0,
            false,
            false,
            false,
            0,
            0,
            objectMapper.writeValueAsString(List.of("unknown.wallet")),
            now);
    var node = standardNode(now);
    when(placementRepository.findForUpdate("ses_1234567890abcdef")).thenReturn(Optional.empty());
    when(demandRepository.findById("ses_1234567890abcdef")).thenReturn(Optional.of(demand));
    when(extensionRepository.findAllById(any())).thenReturn(List.of());
    when(nodeRepository.lockPlacementCandidates(eq("local"), any())).thenReturn(List.of(node));
    when(placementRepository.findAllByNodeIdAndStateIn(eq("node_local"), any()))
        .thenReturn(List.of());
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(placementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var placement = service.reserve(session(ResourceClass.L1), "local");

    assertThat(placement.requestedResourceClass()).isEqualTo(ResourceClass.L1);
    assertThat(placement.effectiveResourceClass()).isEqualTo(ResourceClass.L2);
    assertThat(placement.unknownExtensionCount()).isEqualTo(1);
    assertThat(placement.reasonCodes()).contains("UNKNOWN_EXTENSION_PROBATION");
    assertThat(placement.cpuMillis()).isEqualTo(750);
    assertThat(placement.memoryRequestMib()).isEqualTo(1024);
    var publicJson = objectMapper.writeValueAsString(placement);
    assertThat(publicJson).contains("\"requestedTemplate\":\"standard-lite-v1\"");
    assertThat(publicJson).contains("\"resolvedTemplate\":\"standard-v1\"");
    assertThat(publicJson).doesNotContain("requestedResourceClass");
    assertThat(publicJson).doesNotContain("effectiveResourceClass");
    assertThat(node.getActiveSessions()).isEqualTo(1);
    assertThat(node.getReservedMemoryMib()).isEqualTo(1024);

    var context = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionRepository).updateWithExpectedEpoch(context.capture(), eq(0L));
    assertThat(context.getValue().nodeId()).isEqualTo("node_local");
    assertThat(context.getValue().resourceClass()).isEqualTo(ResourceClass.L2);
    assertThat(context.getValue().contextEpoch()).isEqualTo(1);
  }

  @Test
  void rejectsExtensionSampleThatExceedsProfilingCpuBudget() {
    var now = Instant.now();
    var profile =
        new ExtensionProfileEntity(
            "extension.wallet",
            "Wallet",
            100,
            200,
            0,
            0,
            0,
            0,
            0,
            BigDecimal.ONE,
            new BigDecimal("0.9000"),
            "OBSERVED",
            true,
            false,
            false,
            false,
            now);
    when(extensionRepository.findById("extension.wallet")).thenReturn(Optional.of(profile));
    when(nodeRepository.existsById("node_local")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.recordExtensionSample(
                    "extension.wallet",
                    new RecordExtensionSampleRequest("node_local", 100, 200, false, 26, now),
                    now))
        .isInstanceOf(BrowserCapacityApplicationService.ExtensionProfileRejectedException.class)
        .hasMessage("extension sampling CPU budget exceeded");
  }

  @Test
  void mediaWorkloadUsesIndependentEncoderSlotsWithoutRequiringGpu() throws Exception {
    var now = Instant.now();
    var demand =
        new SessionResourceDemandEntity(
            "ses_1234567890abcdef",
            "tenant-a",
            ResourceClass.L1,
            1,
            0,
            false,
            false,
            true,
            1,
            4000,
            "[]",
            now);
    var node =
        new BrowserNodeEntity(
            "node_local",
            "local",
            "localhost:9090",
            10_000,
            16_384,
            4096,
            0,
            2,
            20,
            10,
            true,
            false,
            true,
            false,
            true,
            "{}",
            now);
    when(placementRepository.findForUpdate("ses_1234567890abcdef")).thenReturn(Optional.empty());
    when(demandRepository.findById("ses_1234567890abcdef")).thenReturn(Optional.of(demand));
    when(extensionRepository.findAllById(any())).thenReturn(List.of());
    when(nodeRepository.lockPlacementCandidates(eq("local"), any())).thenReturn(List.of(node));
    when(placementRepository.findAllByNodeIdAndStateIn(eq("node_local"), any()))
        .thenReturn(List.of());
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(placementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var placement = service.reserve(session(ResourceClass.L1), "local");

    assertThat(placement.effectiveResourceClass()).isEqualTo(ResourceClass.L4);
    assertThat(placement.requiresMedia()).isTrue();
    assertThat(placement.requiresGpu()).isFalse();
    assertThat(placement.mediaSlots()).isEqualTo(1);
    assertThat(node.getReservedMediaSlots()).isEqualTo(1);
    verify(enterpriseOperationsService).requireMediaQuota("tenant-a", 1, 4000);
  }

  @Test
  void migrationRejectsNMinusOneCandidateWithoutGenerationFloorCapability() throws Exception {
    var now = Instant.now();
    var demand =
        new SessionResourceDemandEntity(
            "ses_1234567890abcdef",
            "tenant-a",
            ResourceClass.L2,
            2,
            0,
            false,
            false,
            false,
            0,
            0,
            "[]",
            now);
    var legacyNode =
        new BrowserNodeEntity(
            "node_000_legacy",
            "local",
            "localhost:9091",
            10_000,
            16_384,
            4096,
            0,
            0,
            20,
            10,
            true,
            false,
            false,
            false,
            true,
            "{\"runtime\":\"chromium\"}",
            now);
    var compatibleNode =
        new BrowserNodeEntity(
            "node_compatible",
            "local",
            "localhost:9092",
            10_000,
            16_384,
            4096,
            0,
            0,
            20,
            10,
            true,
            false,
            false,
            false,
            true,
            "{\"runtime\":\"chromium\",\"startRuntimeGenerationFloor\":\"v1\"}",
            now);
    var failedCompatibleNode =
        new BrowserNodeEntity(
            "node_failed",
            "local",
            "localhost:9093",
            10_000,
            16_384,
            4096,
            0,
            0,
            20,
            10,
            true,
            false,
            false,
            false,
            true,
            "{\"runtime\":\"chromium\",\"startRuntimeGenerationFloor\":\"v1\"}",
            now);
    when(placementRepository.findForUpdate("ses_1234567890abcdef")).thenReturn(Optional.empty());
    when(demandRepository.findById("ses_1234567890abcdef")).thenReturn(Optional.of(demand));
    when(extensionRepository.findAllById(any())).thenReturn(List.of());
    // The service deliberately rechecks the label even though the PostgreSQL query also filters it.
    when(nodeRepository.lockMigrationPlacementCandidates(eq("local"), any()))
        .thenReturn(List.of(legacyNode, failedCompatibleNode, compatibleNode));
    when(placementRepository.findAllByNodeIdAndStateIn(eq("node_compatible"), any()))
        .thenReturn(List.of());
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(placementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var placement =
        service.reserveMigrationTarget(
            session(ResourceClass.L2), "local", Set.of("node_source", "node_failed"));

    assertThat(placement.nodeId()).isEqualTo("node_compatible");
    assertThat(compatibleNode.getActiveSessions()).isEqualTo(1);
    assertThat(legacyNode.getActiveSessions()).isZero();
    assertThat(failedCompatibleNode.getActiveSessions()).isZero();
  }

  @Test
  void migrationFailsClosedWhenOnlyNMinusOneCandidatesExist() throws Exception {
    var now = Instant.now();
    var demand =
        new SessionResourceDemandEntity(
            "ses_1234567890abcdef",
            "tenant-a",
            ResourceClass.L2,
            2,
            0,
            false,
            false,
            false,
            0,
            0,
            "[]",
            now);
    var legacyNode =
        new BrowserNodeEntity(
            "node_000_legacy",
            "local",
            "localhost:9091",
            10_000,
            16_384,
            4096,
            0,
            0,
            20,
            10,
            true,
            false,
            false,
            false,
            true,
            "{\"runtime\":\"chromium\"}",
            now);
    when(placementRepository.findForUpdate("ses_1234567890abcdef")).thenReturn(Optional.empty());
    when(demandRepository.findById("ses_1234567890abcdef")).thenReturn(Optional.of(demand));
    when(extensionRepository.findAllById(any())).thenReturn(List.of());
    when(nodeRepository.lockMigrationPlacementCandidates(eq("local"), any()))
        .thenReturn(List.of(legacyNode));

    assertThatThrownBy(
            () -> service.reserveMigrationTarget(session(ResourceClass.L2), "local", "node_source"))
        .isInstanceOf(BrowserCapacityUnavailableException.class)
        .hasMessage("NO_MIGRATION_TARGET_WITH_GENERATION_FLOOR_CAPABILITY");
    assertThat(legacyNode.getActiveSessions()).isZero();
  }

  @Test
  void privilegedExtensionCannotMixWithAnExistingOrdinaryPlacement() throws Exception {
    var now = Instant.now();
    var demand =
        new SessionResourceDemandEntity(
            "ses_1234567890abcdef",
            "tenant-a",
            ResourceClass.L1,
            2,
            0,
            false,
            false,
            false,
            0,
            0,
            objectMapper.writeValueAsString(List.of("wallet.privileged")),
            now);
    var extension =
        new ExtensionProfileEntity(
            "wallet.privileged",
            "Privileged Wallet",
            100,
            128,
            20,
            20,
            64,
            100,
            20,
            BigDecimal.ONE,
            new BigDecimal("0.9"),
            "CERTIFIED",
            true,
            true,
            true,
            true,
            now);
    var node = standardNode(now);
    var existing =
        new BrowserPlacementEntity(
            "ses_ordinary1234567",
            "tenant-b",
            "node_local",
            ResourceClass.L2,
            ResourceClass.L2,
            "[]",
            0,
            600,
            768,
            1280,
            192,
            8,
            false,
            false,
            false,
            false,
            false,
            0,
            0,
            0,
            "[]",
            now);
    when(placementRepository.findForUpdate("ses_1234567890abcdef")).thenReturn(Optional.empty());
    when(demandRepository.findById("ses_1234567890abcdef")).thenReturn(Optional.of(demand));
    when(extensionRepository.findAllById(any())).thenReturn(List.of(extension));
    when(nodeRepository.lockPlacementCandidates(eq("local"), any())).thenReturn(List.of(node));
    when(placementRepository.findAllByNodeIdAndStateIn(eq("node_local"), any()))
        .thenReturn(List.of(existing));

    assertThatThrownBy(() -> service.reserve(session(ResourceClass.L1), "local"))
        .isInstanceOf(BrowserCapacityUnavailableException.class)
        .hasMessage("NO_ELIGIBLE_BROWSER_NODE");
  }

  @Test
  void pressureClosesAdmissionImmediatelyAndRequiresThreeHealthySamplesToReopen() {
    var node = standardNode(Instant.now());
    var now = Instant.now();

    node.recordPressure(
        new BigDecimal("21"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "MEMORY_BURST",
        now);
    assertThat(node.getPressureState()).isEqualTo("CRITICAL");
    assertThat(node.getAdmissionState()).isEqualTo("CLOSED");

    for (int sample = 0; sample < 2; sample++) {
      node.recordPressure(
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          null,
          now.plusSeconds(sample + 1));
    }
    assertThat(node.getAdmissionState()).isEqualTo("CLOSED");

    node.recordPressure(
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        now.plusSeconds(3));
    assertThat(node.getPressureState()).isEqualTo("NORMAL");
    assertThat(node.getAdmissionState()).isEqualTo("OPEN");
  }

  @Test
  void criticalPressureClaimsOnlyOneActivePlacementAndWaitsForSafePoint() {
    var now = Instant.now();
    var placement =
        new BrowserPlacementEntity(
            "ses_1234567890abcdef",
            "tenant-a",
            "node_local",
            ResourceClass.L1,
            ResourceClass.L2,
            "[]",
            1,
            750,
            1024,
            1280,
            192,
            8,
            false,
            false,
            false,
            false,
            false,
            0,
            0,
            0,
            "[]",
            now);
    placement.activate(now);
    when(placementRepository.claimPressureEvictionCandidate()).thenReturn(Optional.of(placement));
    when(placementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var candidate = service.claimPressureEviction().orElseThrow();

    assertThat(candidate.sessionId()).isEqualTo("ses_1234567890abcdef");
    assertThat(candidate.nodeId()).isEqualTo("node_local");
    assertThat(placement.getState()).isEqualTo("WAITING_SAFE_POINT");
    assertThat(placement.getReasonCodes()).contains("NODE_PRESSURE_EVICTION");
  }

  private static BrowserNodeEntity standardNode(Instant now) {
    return new BrowserNodeEntity(
        "node_local",
        "local",
        "localhost:9090",
        10_000,
        16_384,
        4096,
        0,
        0,
        20,
        10,
        true,
        false,
        false,
        false,
        true,
        "{}",
        now);
  }

  private static SessionContext session(ResourceClass resourceClass) {
    var now = Instant.now();
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-a",
        "profile-a",
        null,
        null,
        null,
        null,
        0,
        0,
        0,
        0,
        resourceClass,
        SessionState.CREATED,
        "",
        now,
        now);
  }
}
