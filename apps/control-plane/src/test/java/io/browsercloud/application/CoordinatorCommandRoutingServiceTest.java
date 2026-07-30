package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.OperationResponse;
import io.browsercloud.coordinator.CoordinatorRouteAuthority;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.infrastructure.CoordinatorCommandQueue;
import io.browsercloud.infrastructure.NodeCommandDispatchClaimService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordinatorCommandRoutingServiceTest {

  private final CoordinatorRouteAuthority routes = mock(CoordinatorRouteAuthority.class);
  private final NodeCommandDispatchClaimService membership =
      mock(NodeCommandDispatchClaimService.class);
  private final CoordinatorCommandQueue queue = mock(CoordinatorCommandQueue.class);
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private CoordinatorCommandRoutingService service;

  @BeforeEach
  void setUp() {
    service = new CoordinatorCommandRoutingService(routes, membership, queue, mapper, 30, 2);
    when(routes.resolve("ses_0000000000000001"))
        .thenReturn(
            new CoordinatorRouteAuthority.SessionRoute(
                "ses_0000000000000001", "tenant-test", 4, 7, 11));
  }

  @Test
  void executesDirectlyOnlyWhenThisWorkerOwnsTheShard() {
    when(membership.ownsShard(eq(11), any())).thenReturn(true);
    var expected = new OperationResponse("op_local", OperationState.ACTIVE);

    var result =
        service.execute(
            "ses_0000000000000001",
            "tenant-test",
            "SESSION_START_V1",
            "request-local",
            new CoordinatorCommandPayloads.SessionActor("tenant-test", "actor-test"),
            OperationResponse.class,
            () -> expected);

    assertThat(result).isEqualTo(expected);
    verifyNoInteractions(queue);
  }

  @Test
  void returnsOnlyTheCommittedResultFromTheRemoteShardWorker() throws Exception {
    when(membership.ownsShard(eq(11), any())).thenReturn(false);
    var payload =
        mapper.writeValueAsString(
            new CoordinatorCommandPayloads.SessionActor("tenant-test", "actor-test"));
    var pending = command(payload, "PENDING", null, null);
    var committed =
        command(
            payload,
            "COMMITTED",
            mapper.writeValueAsString(new OperationResponse("op_remote", OperationState.ACTIVE)),
            null);
    when(queue.enqueue(
            any(), eq("SESSION_START_V1"), eq("SESSION_START_V1:request-remote"), any(), any()))
        .thenReturn(pending);
    when(queue.require("ccmd_test")).thenReturn(committed);

    var result =
        service.execute(
            "ses_0000000000000001",
            "tenant-test",
            "SESSION_START_V1",
            "request-remote",
            new CoordinatorCommandPayloads.SessionActor("tenant-test", "actor-test"),
            OperationResponse.class,
            () -> {
              throw new AssertionError("non-owning worker must not execute locally");
            });

    assertThat(result.operationId()).isEqualTo("op_remote");
  }

  @Test
  void rejectsAnIdempotencyReplayWithDifferentPayload() {
    when(membership.ownsShard(eq(11), any())).thenReturn(false);
    var stored =
        command("{\"tenantId\":\"tenant-test\",\"actorId\":\"different\"}", "PENDING", null, null);
    when(queue.enqueue(any(), any(), any(), any(), any())).thenReturn(stored);

    assertThatThrownBy(
            () ->
                service.execute(
                    "ses_0000000000000001",
                    "tenant-test",
                    "SESSION_START_V1",
                    "request-conflict",
                    new CoordinatorCommandPayloads.SessionActor("tenant-test", "actor-test"),
                    OperationResponse.class,
                    () -> null))
        .isInstanceOf(CoordinatorCommandRoutingService.RoutedCoordinatorCommandException.class)
        .hasMessage("COORDINATOR_COMMAND_IDEMPOTENCY_CONFLICT");
  }

  private CoordinatorCommandQueue.CommandRecord command(
      String payload, String state, String result, String failureCode) {
    var now = Instant.now();
    return new CoordinatorCommandQueue.CommandRecord(
        "ccmd_test",
        "tenant-test",
        "ses_0000000000000001",
        4,
        11,
        "SESSION_START_V1",
        "SESSION_START_V1:request-remote",
        payload,
        state,
        result,
        failureCode,
        1,
        null,
        null,
        now.plusSeconds(30),
        now,
        now,
        "PENDING".equals(state) ? null : now);
  }
}
