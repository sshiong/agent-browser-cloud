package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.BrowserNodeView;
import io.browsercloud.application.BrowserCapacityApplicationService;
import io.browsercloud.application.NodeEventIngestionService;
import io.browsercloud.proto.node.v1.ReportCapacityRequest;
import io.browsercloud.proto.node.v1.ReportCapacityResponse;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

  private static StreamObserver<ReportCapacityResponse> observer(
      ArrayList<ReportCapacityResponse> responses) {
    return new StreamObserver<>() {
      @Override
      public void onNext(ReportCapacityResponse value) {
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
