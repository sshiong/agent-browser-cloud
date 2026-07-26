package io.browsercloud.infrastructure;

import io.browsercloud.application.NodeEventIngestionService;
import io.browsercloud.application.NodeEventIngestionService.NodeEventRejectedException;
import io.browsercloud.coordinator.exceptions.StaleCoordinatorTermException;
import io.browsercloud.proto.node.v1.NodeEventServiceGrpc;
import io.browsercloud.proto.node.v1.PublishRequest;
import io.browsercloud.proto.node.v1.PublishResponse;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Browser Node Event gRPC 接收服务。 */
@Component
public class NodeEventGrpcServer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(NodeEventGrpcServer.class);

  private final int port;
  private final NodeEventIngestionService ingestionService;
  private final NodeEventMapper mapper;
  private final GrpcTransportFactory transportFactory;
  private volatile Server server;
  private volatile boolean running;

  public NodeEventGrpcServer(
      @Value("${control-plane.node-event-port:9091}") int port,
      NodeEventIngestionService ingestionService,
      NodeEventMapper mapper,
      GrpcTransportFactory transportFactory) {
    this.port = port;
    this.ingestionService = ingestionService;
    this.mapper = mapper;
    this.transportFactory = transportFactory;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    try {
      server =
          transportFactory
              .nodeEventServer(port)
              .maxInboundMessageSize(128 * 1024)
              .addService(new Endpoint(ingestionService, mapper))
              .build()
              .start();
      running = true;
      log.info(
          "Node Event gRPC server listening on port {} (mTLS={})",
          port,
          transportFactory.tlsEnabled());
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to start Node Event gRPC server", exception);
    }
  }

  @Override
  public synchronized void stop() {
    if (server != null) {
      server.shutdown();
      server = null;
    }
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  private static final class Endpoint extends NodeEventServiceGrpc.NodeEventServiceImplBase {

    private final NodeEventIngestionService ingestionService;
    private final NodeEventMapper mapper;

    private Endpoint(NodeEventIngestionService ingestionService, NodeEventMapper mapper) {
      this.ingestionService = ingestionService;
      this.mapper = mapper;
    }

    @Override
    public void publish(PublishRequest request, StreamObserver<PublishResponse> responseObserver) {
      var eventId = request.hasEvent() ? request.getEvent().getEventId() : "";
      try {
        if (!request.hasEvent()) {
          throw new IllegalArgumentException("event is required");
        }
        var receipt = ingestionService.receive(mapper.toCommand(request.getEvent()));
        respond(
            responseObserver,
            PublishResponse.newBuilder()
                .setEventId(eventId)
                .setAccepted(true)
                .setDuplicate(receipt.duplicate())
                .build());
      } catch (NodeEventRejectedException exception) {
        respond(responseObserver, rejected(eventId, exception.reason(), exception.getMessage()));
      } catch (StaleCoordinatorTermException exception) {
        respond(
            responseObserver, rejected(eventId, "STALE_COORDINATOR_TERM", exception.getMessage()));
      } catch (IllegalArgumentException exception) {
        respond(responseObserver, rejected(eventId, "INVALID_EVENT", exception.getMessage()));
      } catch (RuntimeException exception) {
        log.warn("Node Event {} processing failed", eventId, exception);
        respond(
            responseObserver,
            rejected(eventId, "EVENT_PROCESSING_FAILED", "event processing failed"));
      }
    }

    private PublishResponse rejected(String eventId, String code, String message) {
      return PublishResponse.newBuilder()
          .setEventId(eventId)
          .setAccepted(false)
          .setErrorCode(code)
          .setErrorMessage(message == null ? code : message)
          .build();
    }

    private void respond(
        StreamObserver<PublishResponse> observer, PublishResponse acknowledgement) {
      observer.onNext(acknowledgement);
      observer.onCompleted();
    }
  }
}
