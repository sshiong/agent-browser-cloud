package io.browsercloud.infrastructure;

import io.browsercloud.api.RecordNodePressureRequest;
import io.browsercloud.api.RegisterBrowserNodeRequest;
import io.browsercloud.api.SessionResourceModels.RecordResourceSampleRequest;
import io.browsercloud.application.BrowserCapacityApplicationService;
import io.browsercloud.application.NodeEventIngestionService;
import io.browsercloud.application.NodeEventIngestionService.NodeEventRejectedException;
import io.browsercloud.application.SessionResourceApplicationService;
import io.browsercloud.application.SessionResourceApplicationService.ResourceTelemetryRejectedException;
import io.browsercloud.coordinator.exceptions.StaleCoordinatorTermException;
import io.browsercloud.proto.node.v1.NodeEventServiceGrpc;
import io.browsercloud.proto.node.v1.PublishRequest;
import io.browsercloud.proto.node.v1.PublishResponse;
import io.browsercloud.proto.node.v1.ReportCapacityRequest;
import io.browsercloud.proto.node.v1.ReportCapacityResponse;
import io.browsercloud.proto.node.v1.ReportSessionResourcesRequest;
import io.browsercloud.proto.node.v1.ReportSessionResourcesResponse;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
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
  private final BrowserCapacityApplicationService capacityService;
  private final SessionResourceApplicationService resourceService;
  private final NodeEventMapper mapper;
  private final Validator validator;
  private final GrpcTransportFactory transportFactory;
  private volatile Server server;
  private volatile boolean running;

  public NodeEventGrpcServer(
      @Value("${control-plane.node-event-port:9091}") int port,
      NodeEventIngestionService ingestionService,
      BrowserCapacityApplicationService capacityService,
      SessionResourceApplicationService resourceService,
      NodeEventMapper mapper,
      Validator validator,
      GrpcTransportFactory transportFactory) {
    this.port = port;
    this.ingestionService = ingestionService;
    this.capacityService = capacityService;
    this.resourceService = resourceService;
    this.mapper = mapper;
    this.validator = validator;
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
              .addService(
                  new Endpoint(
                      ingestionService, capacityService, resourceService, mapper, validator))
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

  static final class Endpoint extends NodeEventServiceGrpc.NodeEventServiceImplBase {

    private final NodeEventIngestionService ingestionService;
    private final BrowserCapacityApplicationService capacityService;
    private final SessionResourceApplicationService resourceService;
    private final NodeEventMapper mapper;
    private final Validator validator;

    Endpoint(
        NodeEventIngestionService ingestionService,
        BrowserCapacityApplicationService capacityService,
        SessionResourceApplicationService resourceService,
        NodeEventMapper mapper,
        Validator validator) {
      this.ingestionService = ingestionService;
      this.capacityService = capacityService;
      this.resourceService = resourceService;
      this.mapper = mapper;
      this.validator = validator;
    }

    @Override
    public void reportCapacity(
        ReportCapacityRequest request, StreamObserver<ReportCapacityResponse> responseObserver) {
      try {
        var registration =
            new RegisterBrowserNodeRequest(
                request.getRegion(),
                request.getGrpcTarget(),
                request.getCertifiedCpuMillis(),
                request.getCertifiedMemoryMib(),
                request.getCertifiedPidCount(),
                request.getCertifiedGpuSlots(),
                request.getCertifiedMediaSlots(),
                request.getSafetyMarginPercent(),
                request.getMaxSessions(),
                request.getSupportsDesktop(),
                request.getSupportsGpu(),
                request.getSupportsMedia(),
                request.getSupportsNativeOs(),
                request.getIsolationCapable(),
                request.getLabelsMap());
        var pressure =
            new RecordNodePressureRequest(
                decimal(request.getMemoryPsiSomeAvg10()),
                decimal(request.getMemoryPsiFullAvg10()),
                decimal(request.getCpuPsiSomeAvg10()),
                decimal(request.getIoPsiFullAvg10()),
                request.getPressureReason());
        validate(registration);
        validate(pressure);
        var now = Instant.now();
        capacityService.registerNode(request.getNodeId(), registration, now);
        var node = capacityService.recordPressure(request.getNodeId(), pressure, now);
        respond(
            responseObserver,
            ReportCapacityResponse.newBuilder()
                .setNodeId(request.getNodeId())
                .setAccepted(true)
                .setAdmissionState(node.admissionState())
                .setPressureState(node.pressureState())
                .build());
      } catch (IllegalArgumentException exception) {
        respond(
            responseObserver,
            capacityRejected(
                request.getNodeId(), "INVALID_CAPACITY_REPORT", exception.getMessage()));
      } catch (RuntimeException exception) {
        log.warn("Browser Node {} capacity report failed", request.getNodeId(), exception);
        respond(
            responseObserver,
            capacityRejected(
                request.getNodeId(), "CAPACITY_REPORT_FAILED", "capacity report failed"));
      }
    }

    @Override
    public void reportSessionResources(
        ReportSessionResourcesRequest request,
        StreamObserver<ReportSessionResourcesResponse> responseObserver) {
      try {
        var sample =
            new RecordResourceSampleRequest(
                request.getNodeId(),
                request.hasCpuPercent() ? request.getCpuPercent() : null,
                request.hasMemoryRssMib() ? Math.toIntExact(request.getMemoryRssMib()) : null,
                request.hasMemoryPsiSomeAvg10() ? request.getMemoryPsiSomeAvg10() : null,
                request.hasRendererCount() ? request.getRendererCount() : null,
                request.hasTabCount() ? request.getTabCount() : null,
                request.hasMainThreadBlockedMs() ? request.getMainThreadBlockedMs() : null,
                request.hasAgentActionLatencyMs() ? request.getAgentActionLatencyMs() : null,
                request.hasStateDiffQueueDepth() ? request.getStateDiffQueueDepth() : null,
                request.hasProfileIoBytesPerSecond() ? request.getProfileIoBytesPerSecond() : null,
                request.hasExtensionCpuPercent() ? request.getExtensionCpuPercent() : null,
                request.hasExtensionMemoryMib()
                    ? Math.toIntExact(request.getExtensionMemoryMib())
                    : null,
                request.hasRemoteDesktopFrameAgeMs() ? request.getRemoteDesktopFrameAgeMs() : null,
                request.hasMediaEncoderPercent() ? request.getMediaEncoderPercent() : null,
                request.getDangerEvent(),
                Instant.ofEpochMilli(request.getObservedAtMs()));
        validate(sample);
        resourceService.recordSampleFromNode(
            request.getSessionId(), request.getTenantId(), request.getContextEpoch(), sample);
        respond(
            responseObserver,
            ReportSessionResourcesResponse.newBuilder()
                .setSessionId(request.getSessionId())
                .setAccepted(true)
                .build());
      } catch (IllegalArgumentException | ArithmeticException exception) {
        respond(
            responseObserver,
            resourceRejected(
                request.getSessionId(), "INVALID_RESOURCE_SAMPLE", exception.getMessage()));
      } catch (ResourceTelemetryRejectedException exception) {
        respond(
            responseObserver,
            resourceRejected(
                request.getSessionId(), exception.getMessage(), "resource sample rejected"));
      } catch (RuntimeException exception) {
        log.warn(
            "Browser Node {} Session {} resource report failed",
            request.getNodeId(),
            request.getSessionId(),
            exception);
        respond(
            responseObserver,
            resourceRejected(
                request.getSessionId(),
                "RESOURCE_SAMPLE_PROCESSING_FAILED",
                "resource sample processing failed"));
      }
    }

    private static BigDecimal decimal(double value) {
      if (!Double.isFinite(value)) {
        throw new IllegalArgumentException("PSI value must be finite");
      }
      return BigDecimal.valueOf(value);
    }

    private <T> void validate(T request) {
      var violations = validator.validate(request);
      if (!violations.isEmpty()) {
        var message =
            violations.stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .findFirst()
                .orElse("invalid capacity report");
        throw new IllegalArgumentException(message);
      }
    }

    private ReportCapacityResponse capacityRejected(String nodeId, String code, String message) {
      return ReportCapacityResponse.newBuilder()
          .setNodeId(nodeId)
          .setAccepted(false)
          .setErrorCode(code)
          .setErrorMessage(message == null ? code : message)
          .build();
    }

    private ReportSessionResourcesResponse resourceRejected(
        String sessionId, String code, String message) {
      return ReportSessionResourcesResponse.newBuilder()
          .setSessionId(sessionId)
          .setAccepted(false)
          .setErrorCode(code == null ? "RESOURCE_SAMPLE_REJECTED" : code)
          .setErrorMessage(message == null ? "resource sample rejected" : message)
          .build();
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

    private <T> void respond(StreamObserver<T> observer, T acknowledgement) {
      observer.onNext(acknowledgement);
      observer.onCompleted();
    }
  }
}
