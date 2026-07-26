package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.application.AgentActionPayloadService;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.OutboxEventEntity;
import io.browsercloud.proto.node.v1.CommandAck;
import io.browsercloud.proto.node.v1.DispatchRequest;
import io.browsercloud.proto.node.v1.DispatchResponse;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NodeCommandMultiNodeRoutingTest {

  private final List<Server> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    servers.forEach(Server::shutdownNow);
  }

  @Test
  void dispatchesEachPlacementCommandOnlyToItsRegisteredNodeTarget() throws Exception {
    var nodeOneCommands = new ArrayList<String>();
    var nodeTwoCommands = new ArrayList<String>();
    var serverOne = startServer(nodeOneCommands);
    var serverTwo = startServer(nodeTwoCommands);
    var mapper = new ObjectMapper();
    var eventOne =
        outbox(
            mapper,
            new NodeCommand(
                "msg_node_one",
                "StopRuntime",
                "node_one",
                "ses_0000000000000001",
                "tenant-a",
                1,
                1,
                1,
                "stop-one",
                new byte[0]));
    var eventTwo =
        outbox(
            mapper,
            new NodeCommand(
                "msg_node_two",
                "StopRuntime",
                "node_two",
                "ses_0000000000000002",
                "tenant-a",
                1,
                1,
                1,
                "stop-two",
                new byte[0]));

    var outboxRepository = mock(OutboxEventJpaRepository.class);
    when(outboxRepository
            .findTop100ByPublishedAtIsNullAndDeadLetteredAtIsNullAndEventTypeAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                anyString(), any()))
        .thenReturn(List.of(eventOne, eventTwo));
    when(outboxRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var nodeRepository = mock(BrowserNodeJpaRepository.class);
    when(nodeRepository.findById("node_one"))
        .thenReturn(Optional.of(node("node_one", serverOne.getPort())));
    when(nodeRepository.findById("node_two"))
        .thenReturn(Optional.of(node("node_two", serverTwo.getPort())));
    var dispatcher =
        new NodeCommandOutboxDispatcher(
            outboxRepository,
            mapper,
            mock(AgentActionPayloadService.class),
            nodeRepository,
            new GrpcTransportFactory("local", false, "", "", "", "browser-node.internal"),
            "127.0.0.1:1");

    dispatcher.dispatchPending();
    dispatcher.closeChannels();

    assertThat(nodeOneCommands).containsExactly("ses_0000000000000001");
    assertThat(nodeTwoCommands).containsExactly("ses_0000000000000002");
    assertThat(eventOne.getPublishedAt()).isNotNull();
    assertThat(eventTwo.getPublishedAt()).isNotNull();
  }

  private Server startServer(List<String> sessions) throws Exception {
    var server =
        NettyServerBuilder.forPort(0)
            .addService(
                new NodeControlServiceGrpc.NodeControlServiceImplBase() {
                  @Override
                  public void dispatch(
                      DispatchRequest request, StreamObserver<DispatchResponse> observer) {
                    sessions.add(request.getCommand().getSessionId());
                    observer.onNext(
                        DispatchResponse.newBuilder()
                            .setAcknowledgement(
                                CommandAck.newBuilder()
                                    .setMessageId(request.getCommand().getMessageId())
                                    .setAccepted(true))
                            .build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    servers.add(server);
    return server;
  }

  private static BrowserNodeEntity node(String nodeId, int port) {
    return new BrowserNodeEntity(
        nodeId,
        "local",
        "127.0.0.1:" + port,
        4_000,
        8_192,
        2_048,
        0,
        0,
        20,
        8,
        true,
        false,
        false,
        false,
        true,
        "{}",
        Instant.now());
  }

  private static OutboxEventEntity outbox(ObjectMapper mapper, NodeCommand command)
      throws Exception {
    var event = new OutboxEventEntity();
    event.setEventId("evt_" + command.messageId());
    event.setAggregateType("Session");
    event.setAggregateId(command.sessionId());
    event.setEventType(PostgresNodeCommandGateway.NODE_COMMAND_EVENT);
    event.setSchemaVersion(1);
    event.setPayload(mapper.writeValueAsString(command));
    event.setCreatedAt(Instant.now());
    event.setNextAttemptAt(Instant.EPOCH);
    return event;
  }
}
