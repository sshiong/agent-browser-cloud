package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.BrowserNodeView;
import io.browsercloud.api.SafePointModels.NodeSafetyObservation;
import io.browsercloud.api.SessionResourceModels.RecordResourceSampleRequest;
import io.browsercloud.application.BrowserCapacityApplicationService;
import io.browsercloud.application.NodeEventIngestionService;
import io.browsercloud.application.SafePointApplicationService;
import io.browsercloud.application.SessionResourceApplicationService;
import io.browsercloud.proto.node.v1.ReportCapacityRequest;
import io.browsercloud.proto.node.v1.ReportCapacityResponse;
import io.browsercloud.proto.node.v1.ReportSessionResourcesRequest;
import io.browsercloud.proto.node.v1.ReportSessionResourcesResponse;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NodeCapacityGrpcEndpointTest {

  @Test
  void authenticNodeReportRegistersCapacityAndCommitsPressureSample() {
    var capacity = mock(BrowserCapacityApplicationService.class);
    var now = Instant.now();
    when(capacity.recordPressure(eq("node_test_1"), any(), any()))
        .thenReturn(
            new BrowserNodeView(
                "node_test_1",
                "local",
                "node-test:9090",
                "READY",
                "OPEN",
                4000,
                8192,
                2048,
                0,
                0,
                20,
                0,
                0,
                0,
                0,
                0,
                0,
                8,
                new BigDecimal("2.5"),
                BigDecimal.ZERO,
                new BigDecimal("4.5"),
                BigDecimal.ZERO,
                "NORMAL",
                null,
                true,
                false,
                false,
                false,
                true,
                Map.of("resourceEnforcement", "cgroup-v2"),
                now,
                now));
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              mock(NodeEventIngestionService.class),
              capacity,
              mock(SessionResourceApplicationService.class),
              mock(io.browsercloud.application.SessionResourceDecisionExecutor.class),
              mock(SafePointApplicationService.class),
              mock(io.browsercloud.application.ProxyBindingHealthApplicationService.class),
              mock(NodeEventMapper.class),
              factory.getValidator());
      var responses = new ArrayList<ReportCapacityResponse>();
      endpoint.reportCapacity(
          ReportCapacityRequest.newBuilder()
              .setNodeId("node_test_1")
              .setRegion("local")
              .setGrpcTarget("node-test:9090")
              .setCertifiedCpuMillis(4000)
              .setCertifiedMemoryMib(8192)
              .setCertifiedPidCount(2048)
              .setSafetyMarginPercent(20)
              .setMaxSessions(8)
              .setSupportsDesktop(true)
              .setIsolationCapable(true)
              .putLabels("resourceEnforcement", "cgroup-v2")
              .setMemoryPsiSomeAvg10(2.5)
              .setCpuPsiSomeAvg10(4.5)
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .satisfies(
              response -> {
                assertThat(response.getAccepted()).isTrue();
                assertThat(response.getAdmissionState()).isEqualTo("OPEN");
                assertThat(response.getPressureState()).isEqualTo("NORMAL");
              });
      verify(capacity).registerNode(eq("node_test_1"), any(), any());
      verify(capacity).recordPressure(eq("node_test_1"), any(), any());
    }
  }

  @Test
  void authenticNodeResourceReportRecordsRealSessionSample() {
    var resources = mock(SessionResourceApplicationService.class);
    var decisions = mock(io.browsercloud.application.SessionResourceDecisionExecutor.class);
    var safePoints = mock(SafePointApplicationService.class);
    var proxyHealth = mock(io.browsercloud.application.ProxyBindingHealthApplicationService.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              mock(NodeEventIngestionService.class),
              mock(BrowserCapacityApplicationService.class),
              resources,
              decisions,
              safePoints,
              proxyHealth,
              mock(NodeEventMapper.class),
              factory.getValidator());
      var responses = new ArrayList<ReportSessionResourcesResponse>();
      endpoint.reportSessionResources(
          ReportSessionResourcesRequest.newBuilder()
              .setNodeId("node_test_1")
              .setTenantId("tenant-test")
              .setSessionId("ses_test_1")
              .setContextEpoch(7)
              .setObservedAtMs(Instant.now().toEpochMilli())
              .setCpuPercent(64.5)
              .setMemoryRssMib(1536)
              .setMemoryPsiSomeAvg10(2.75)
              .setProfileIoBytesPerSecond(52_428_800)
              .setDangerEvent("OOM")
              .setInputActive(true)
              .setActiveDrag(false)
              .setPressedKeyCount(1)
              .setPressedButtonCount(0)
              .setProxyProbeSucceeded(true)
              .setProxyProbeLatencyMs(87)
              .setProxyObservedExitIp("203.0.113.10")
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .satisfies(
              response -> {
                assertThat(response.getSessionId()).isEqualTo("ses_test_1");
                assertThat(response.getAccepted()).isTrue();
              });
      var resourceSample = ArgumentCaptor.forClass(RecordResourceSampleRequest.class);
      verify(resources)
          .recordSampleFromNode(
              eq("ses_test_1"), eq("tenant-test"), eq(7L), resourceSample.capture());
      assertThat(resourceSample.getValue().profileIoBytesPerSecond()).isEqualTo(52_428_800);
      assertThat(resourceSample.getValue().dangerEvent()).isEqualTo("OOM");
      verify(decisions).dispatchPending("ses_test_1");
      verify(safePoints)
          .recordNodeObservation(
              eq("ses_test_1"), eq("tenant-test"), eq("node_test_1"), eq(7L), any());
      verify(proxyHealth)
          .recordNodeProbe(eq("ses_test_1"), eq("tenant-test"), eq("node_test_1"), any(), any());
    }
  }

  @Test
  void authenticNodeResourceReportRecordsCompleteBrowserActivityObservation() {
    var resources = mock(SessionResourceApplicationService.class);
    var safePoints = mock(SafePointApplicationService.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              mock(NodeEventIngestionService.class),
              mock(BrowserCapacityApplicationService.class),
              resources,
              mock(io.browsercloud.application.SessionResourceDecisionExecutor.class),
              safePoints,
              mock(io.browsercloud.application.ProxyBindingHealthApplicationService.class),
              mock(NodeEventMapper.class),
              factory.getValidator());
      var responses = new ArrayList<ReportSessionResourcesResponse>();
      endpoint.reportSessionResources(
          ReportSessionResourcesRequest.newBuilder()
              .setNodeId("node_test_1")
              .setTenantId("tenant-test")
              .setSessionId("ses_test_1")
              .setContextEpoch(7)
              .setObservedAtMs(Instant.now().toEpochMilli())
              .setActiveUploadCount(1)
              .setActiveDownloadCount(2)
              .setActiveFormSubmissionCount(3)
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .extracting(ReportSessionResourcesResponse::getAccepted)
          .isEqualTo(true);
      var observation = ArgumentCaptor.forClass(NodeSafetyObservation.class);
      verify(safePoints)
          .recordNodeObservation(
              eq("ses_test_1"),
              eq("tenant-test"),
              eq("node_test_1"),
              eq(7L),
              observation.capture());
      assertThat(observation.getValue().activeUploadCount()).isEqualTo(1);
      assertThat(observation.getValue().activeDownloadCount()).isEqualTo(2);
      assertThat(observation.getValue().activeFormSubmissionCount()).isEqualTo(3);
    }
  }

  @Test
  void completeActualAllocationIsForwardedForAuthoritativeReadback() {
    var resources = mock(SessionResourceApplicationService.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              mock(NodeEventIngestionService.class),
              mock(BrowserCapacityApplicationService.class),
              resources,
              mock(io.browsercloud.application.SessionResourceDecisionExecutor.class),
              mock(SafePointApplicationService.class),
              mock(io.browsercloud.application.ProxyBindingHealthApplicationService.class),
              mock(NodeEventMapper.class),
              factory.getValidator());
      var responses = new ArrayList<ReportSessionResourcesResponse>();
      endpoint.reportSessionResources(
          ReportSessionResourcesRequest.newBuilder()
              .setNodeId("node_test_1")
              .setTenantId("tenant-test")
              .setSessionId("ses_test_1")
              .setContextEpoch(7)
              .setObservedAtMs(Instant.now().toEpochMilli())
              .setActualResourceClass("L2")
              .setActualCpuMillis(600)
              .setActualMemoryRequestMib(768)
              .setActualMemoryLimitMib(1280)
              .setActualPidLimit(192)
              .setActualTabBudget(8)
              .setActualStateCollectorBudgetPercent(100)
              .setActualRemoteDesktopBitrateKbps(0)
              .setActualExtensionCpuWeight(100)
              .setActualMediaEncoderSlots(0)
              .setActualFreezeBackgroundTabs(false)
              .setActualBlockNewTabs(false)
              .setActualExtensionBackgroundPolicy(
                  io.browsercloud.proto.node.v1.ExtensionBackgroundPolicy.newBuilder().build())
              .setActualSuccessTraceSamplePercent(100)
              .setActualObserverFrameRateFps(0)
              .setActualVideoRecordingEnabled(false)
              .setActualSuccessScreenshotSamplePercent(100)
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .extracting(ReportSessionResourcesResponse::getAccepted)
          .isEqualTo(true);
      var readback =
          ArgumentCaptor.forClass(
              io.browsercloud.api.SessionResourceModels.NodeResourceAllocationReadback.class);
      verify(resources)
          .recordSampleFromNode(
              eq("ses_test_1"), eq("tenant-test"), eq(7L), any(), readback.capture());
      assertThat(readback.getValue().resourceClass()).isEqualTo("L2");
      assertThat(readback.getValue().memoryLimitMib()).isEqualTo(1280);
    }
  }

  @Test
  void partialActualAllocationIsRejectedBeforePersistence() {
    var resources = mock(SessionResourceApplicationService.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              mock(NodeEventIngestionService.class),
              mock(BrowserCapacityApplicationService.class),
              resources,
              mock(io.browsercloud.application.SessionResourceDecisionExecutor.class),
              mock(SafePointApplicationService.class),
              mock(io.browsercloud.application.ProxyBindingHealthApplicationService.class),
              mock(NodeEventMapper.class),
              factory.getValidator());
      var responses = new ArrayList<ReportSessionResourcesResponse>();
      endpoint.reportSessionResources(
          ReportSessionResourcesRequest.newBuilder()
              .setNodeId("node_test_1")
              .setTenantId("tenant-test")
              .setSessionId("ses_test_1")
              .setContextEpoch(7)
              .setObservedAtMs(Instant.now().toEpochMilli())
              .setActualCpuMillis(600)
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .satisfies(
              response -> {
                assertThat(response.getAccepted()).isFalse();
                assertThat(response.getErrorCode()).isEqualTo("INVALID_RESOURCE_SAMPLE");
              });
      verify(resources, never()).recordSampleFromNode(any(), any(), eq(7L), any());
      verify(resources, never()).recordSampleFromNode(any(), any(), eq(7L), any(), any());
    }
  }

  @Test
  void partialBrowserActivityObservationIsRejectedBeforePersistence() {
    var resources = mock(SessionResourceApplicationService.class);
    var safePoints = mock(SafePointApplicationService.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              mock(NodeEventIngestionService.class),
              mock(BrowserCapacityApplicationService.class),
              resources,
              mock(io.browsercloud.application.SessionResourceDecisionExecutor.class),
              safePoints,
              mock(io.browsercloud.application.ProxyBindingHealthApplicationService.class),
              mock(NodeEventMapper.class),
              factory.getValidator());
      var responses = new ArrayList<ReportSessionResourcesResponse>();
      endpoint.reportSessionResources(
          ReportSessionResourcesRequest.newBuilder()
              .setNodeId("node_test_1")
              .setTenantId("tenant-test")
              .setSessionId("ses_test_1")
              .setContextEpoch(7)
              .setObservedAtMs(Instant.now().toEpochMilli())
              .setActiveUploadCount(1)
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .satisfies(
              response -> {
                assertThat(response.getAccepted()).isFalse();
                assertThat(response.getErrorCode()).isEqualTo("INVALID_RESOURCE_SAMPLE");
              });
      verify(resources, never()).recordSampleFromNode(any(), any(), eq(7L), any());
      verify(safePoints, never()).recordNodeObservation(any(), any(), any(), eq(7L), any());
    }
  }

  @Test
  void partialProxyProbeObservationIsRejectedBeforePersistence() {
    var resources = mock(SessionResourceApplicationService.class);
    var proxyHealth = mock(io.browsercloud.application.ProxyBindingHealthApplicationService.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              mock(NodeEventIngestionService.class),
              mock(BrowserCapacityApplicationService.class),
              resources,
              mock(io.browsercloud.application.SessionResourceDecisionExecutor.class),
              mock(SafePointApplicationService.class),
              proxyHealth,
              mock(NodeEventMapper.class),
              factory.getValidator());
      var responses = new ArrayList<ReportSessionResourcesResponse>();
      endpoint.reportSessionResources(
          ReportSessionResourcesRequest.newBuilder()
              .setNodeId("node_test_1")
              .setTenantId("tenant-test")
              .setSessionId("ses_test_1")
              .setContextEpoch(7)
              .setObservedAtMs(Instant.now().toEpochMilli())
              .setProxyProbeSucceeded(false)
              .setProxyProbeLatencyMs(20)
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .satisfies(
              response -> {
                assertThat(response.getAccepted()).isFalse();
                assertThat(response.getErrorCode()).isEqualTo("INVALID_RESOURCE_SAMPLE");
              });
      verify(resources, never()).recordSampleFromNode(any(), any(), eq(7L), any());
      verify(proxyHealth, never()).recordNodeProbe(any(), any(), any(), any(), any());
    }
  }

  private static <T> StreamObserver<T> observer(ArrayList<T> responses) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable throwable) {
        throw new AssertionError(throwable);
      }

      @Override
      public void onCompleted() {}
    };
  }
}
