package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.browsercloud.application.BrowserCapacityApplicationService;
import io.browsercloud.application.NodeEventIngestionService;
import io.browsercloud.application.ProxyBindingHealthApplicationService;
import io.browsercloud.application.SafePointApplicationService;
import io.browsercloud.application.SessionResourceApplicationService;
import io.browsercloud.application.SessionResourceDecisionExecutor;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.exceptions.CoordinatorNotOwnerException;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.PublishRequest;
import io.browsercloud.proto.node.v1.PublishResponse;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validation;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class NodeEventGrpcEndpointTest {

  @Test
  void expiredCoordinatorLeaseIsReturnedAsATerminalFenceInsteadOfARetryableFailure() {
    var ingestion = mock(NodeEventIngestionService.class);
    var mapper = mock(NodeEventMapper.class);
    when(mapper.toCommand(any(EventEnvelope.class))).thenReturn(mock(NodeEventReceived.class));
    when(ingestion.receive(any(NodeEventReceived.class)))
        .thenThrow(new CoordinatorNotOwnerException("ses_failover"));

    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      var endpoint =
          new NodeEventGrpcServer.Endpoint(
              ingestion,
              mock(BrowserCapacityApplicationService.class),
              mock(SessionResourceApplicationService.class),
              mock(SessionResourceDecisionExecutor.class),
              mock(SafePointApplicationService.class),
              mock(ProxyBindingHealthApplicationService.class),
              mapper,
              validatorFactory.getValidator());
      var responses = new ArrayList<PublishResponse>();

      endpoint.publish(
          PublishRequest.newBuilder()
              .setEvent(EventEnvelope.newBuilder().setEventId("evt_stale_lease").build())
              .build(),
          observer(responses));

      assertThat(responses)
          .singleElement()
          .satisfies(
              response -> {
                assertThat(response.getAccepted()).isFalse();
                assertThat(response.getErrorCode()).isEqualTo("STALE_COORDINATOR_LEASE");
              });
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
