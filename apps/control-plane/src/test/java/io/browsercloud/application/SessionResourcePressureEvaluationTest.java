package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.api.SessionResourceModels.RecordResourceSampleRequest;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.BrowserPlacementEntity;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.ExtensionProfileJpaRepository;
import io.browsercloud.persistence.SessionResourceCostSnapshotJpaRepository;
import io.browsercloud.persistence.SessionResourceEventJpaRepository;
import io.browsercloud.persistence.SessionResourcePolicyEntity;
import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import io.browsercloud.persistence.SessionResourceSampleEntity;
import io.browsercloud.persistence.SessionResourceSampleJpaRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionResourcePressureEvaluationTest {

  private final SessionResourceApplicationService service =
      new SessionResourceApplicationService(
          mock(SessionResourcePolicyJpaRepository.class),
          mock(SessionResourceSampleJpaRepository.class),
          mock(SessionResourceEventJpaRepository.class),
          mock(SessionResourceCostSnapshotJpaRepository.class),
          mock(BrowserPlacementJpaRepository.class),
          mock(ExtensionProfileJpaRepository.class),
          mock(AgentTaskJpaRepository.class),
          mock(SessionRepository.class),
          mock(OperationRepository.class),
          mock(IdempotencyService.class),
          mock(NodeCommandGateway.class),
          mock(SafePointApplicationService.class),
          mock(EnterpriseOperationsApplicationService.class),
          new ObjectMapper());

  @Test
  void sustainedAgentLatencyTriggersScaleUpWithoutCpuOrMemoryPressure() {
    var now = Instant.parse("2026-07-28T00:00:00Z");
    var policy = policy(now, 30, 1_200);
    var placement = placement(now);
    var samples =
        List.of(
            sample(now.minusSeconds(30), 10.0, 500, 2_000, null),
            sample(now, 12.0, 510, 2_100, null));

    assertThat(service.scaleUpPressureReason(samples, policy, placement))
        .isEqualTo("SUSTAINED_AGENT_ACTION_LATENCY");
  }

  @Test
  void recentRemoteDesktopFramePressureBlocksScaleDownHysteresis() {
    var now = Instant.parse("2026-07-28T00:00:00Z");
    var policy = policy(now, 30, 1_200);
    var placement = placement(now);
    var samples = List.of(sample(now.minusSeconds(10), 10.0, 500, null, 800));

    assertThat(service.hasSecondaryLoadInScaleDownWindow(samples, policy, placement, now)).isTrue();
  }

  @Test
  void sustainedProfileIoTriggersScaleUpFromRealNodeRate() {
    var now = Instant.parse("2026-07-28T00:00:00Z");
    var policy = policy(now, 30, 1_200);
    var placement = placement(now);
    var sustainedRate = 60L * 1024 * 1024;
    var samples =
        List.of(
            sampleWithProfileIo(now.minusSeconds(30), sustainedRate),
            sampleWithProfileIo(now, sustainedRate));

    assertThat(service.scaleUpPressureReason(samples, policy, placement))
        .isEqualTo("SUSTAINED_PROFILE_IO_PRESSURE");
  }

  @Test
  void safetyOnlySampleDoesNotDiluteSustainedCpuPressure() {
    var now = Instant.parse("2026-07-28T00:00:00Z");
    var policy = policy(now, 60, 1_200);
    var placement = placement(now);
    var samples =
        List.of(
            sample(now.minusSeconds(61), 100.0, 640, null, null),
            sample(now.minusSeconds(3), null, null, null, null),
            sample(now.minusSeconds(2), 42.5, 640, null, null),
            sample(now, 100.0, 640, null, null));

    assertThat(service.scaleUpPressureReason(samples, policy, placement))
        .isEqualTo("SUSTAINED_CPU_PRESSURE");
  }

  @Test
  void emptyDangerEventFromNodeTelemetryIsNotCritical() {
    var now = Instant.parse("2026-07-28T00:00:00Z");

    assertThat(
            service.hasDangerEvent(
                List.of(sample(now, null, null, null, null), sample(now, 10.0, 100, null, null))))
        .isFalse();
    assertThat(service.hasDangerEvent(List.of(sample(now, 10.0, 100, null, null, "OOM")))).isTrue();
  }

  @Test
  void initialPlacementDoesNotStartAdjustmentCooldown() {
    var now = Instant.parse("2026-07-28T00:00:00Z");
    var policy = policy(now, 30, 1_200);

    policy.resolveTemplate("standard-v1", now.plusSeconds(1));

    assertThat(policy.getLastAdjustedAt()).isNull();
  }

  private static SessionResourcePolicyEntity policy(
      Instant now, int scaleUpWindowSeconds, int scaleDownWindowSeconds) {
    return SessionResourcePolicyEntity.create(
        "ses_pressure",
        "tenant-test",
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
            null,
            scaleUpWindowSeconds,
            scaleDownWindowSeconds,
            null),
        now);
  }

  private static BrowserPlacementEntity placement(Instant now) {
    var placement =
        new BrowserPlacementEntity(
            "ses_pressure",
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

  private static SessionResourceSampleEntity sample(
      Instant observedAt,
      Double cpuPercent,
      Integer memoryRssMib,
      Integer agentActionLatencyMs,
      Integer remoteDesktopFrameAgeMs) {
    return sample(
        observedAt, cpuPercent, memoryRssMib, agentActionLatencyMs, remoteDesktopFrameAgeMs, "");
  }

  private static SessionResourceSampleEntity sample(
      Instant observedAt,
      Double cpuPercent,
      Integer memoryRssMib,
      Integer agentActionLatencyMs,
      Integer remoteDesktopFrameAgeMs,
      String dangerEvent) {
    return new SessionResourceSampleEntity(
        "rs_" + observedAt.toEpochMilli(),
        "ses_pressure",
        "tenant-test",
        new RecordResourceSampleRequest(
            "node_test",
            cpuPercent,
            memoryRssMib,
            0.0,
            2,
            1,
            100,
            agentActionLatencyMs,
            0,
            0L,
            0.0,
            0,
            remoteDesktopFrameAgeMs,
            0.0,
            dangerEvent,
            observedAt),
        observedAt);
  }

  private static SessionResourceSampleEntity sampleWithProfileIo(
      Instant observedAt, long profileIoBytesPerSecond) {
    return new SessionResourceSampleEntity(
        "rs_io_" + observedAt.toEpochMilli(),
        "ses_pressure",
        "tenant-test",
        new RecordResourceSampleRequest(
            "node_test",
            10.0,
            500,
            0.0,
            2,
            1,
            100,
            100,
            0,
            profileIoBytesPerSecond,
            null,
            null,
            null,
            null,
            "",
            observedAt),
        observedAt);
  }
}
