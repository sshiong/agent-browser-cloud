package io.browsercloud.infrastructure;

import com.google.protobuf.ByteString;
import io.browsercloud.application.AgentBrowserFileStageNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.StageAgentBrowserFileRequest;
import io.browsercloud.proto.node.v1.StageAgentBrowserFileResponse;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Exact-Node mTLS stream for Agent file bytes; no byte or staging path enters PostgreSQL. */
@Component
public class GrpcAgentBrowserFileStageNodeGateway implements AgentBrowserFileStageNodeGateway {
  private static final int CHUNK_BYTES = 256 * 1024;
  private static final long NODE_FRESHNESS_SECONDS = 45;

  private final BrowserNodeJpaRepository nodes;
  private final GrpcTransportFactory transportFactory;
  private final Map<String, ManagedChannel> channels =
      new java.util.concurrent.ConcurrentHashMap<>();

  public GrpcAgentBrowserFileStageNodeGateway(
      BrowserNodeJpaRepository nodes, GrpcTransportFactory transportFactory) {
    this.nodes = nodes;
    this.transportFactory = transportFactory;
  }

  @Override
  public StageResult stage(StageRequest request, InputStream content) {
    var node =
        nodes
            .findAgentBrowserFileNode(
                request.nodeId(), Instant.now().minusSeconds(NODE_FRESHNESS_SECONDS))
            .orElseThrow(() -> new StageRejectedException("AGENT_FILE_NODE_INCOMPATIBLE"));
    var channel =
        channels.compute(
            node.getNodeId(),
            (ignored, existing) ->
                existing != null && !existing.isShutdown()
                    ? existing
                    : transportFactory.nodeChannel(node.getGrpcTarget()));
    var deadlineSeconds =
        Math.min(120L, Math.max(20L, 20L + request.contentBytes() / (2 * 1024 * 1024)));
    var response = new CompletableFuture<StageAgentBrowserFileResponse>();
    var requestStream =
        new AtomicReference<ClientCallStreamObserver<StageAgentBrowserFileRequest>>();
    var monitor = new Object();
    var observer =
        new ClientResponseObserver<StageAgentBrowserFileRequest, StageAgentBrowserFileResponse>() {
          @Override
          public void beforeStart(ClientCallStreamObserver<StageAgentBrowserFileRequest> stream) {
            requestStream.set(stream);
            stream.setOnReadyHandler(
                () -> {
                  synchronized (monitor) {
                    monitor.notifyAll();
                  }
                });
          }

          @Override
          public void onNext(StageAgentBrowserFileResponse value) {
            response.complete(value);
          }

          @Override
          public void onError(Throwable failure) {
            response.completeExceptionally(failure);
            synchronized (monitor) {
              monitor.notifyAll();
            }
          }

          @Override
          public void onCompleted() {
            if (!response.isDone()) {
              response.completeExceptionally(
                  new IllegalStateException("Browser Node omitted file stage response"));
            }
          }
        };
    NodeControlServiceGrpc.newStub(channel)
        .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
        .stageAgentBrowserFile(observer);
    var outbound = awaitStream(requestStream, response, monitor);
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var buffer = new byte[CHUNK_BYTES];
      long offset = 0;
      while (true) {
        var read = content.read(buffer);
        if (read < 0) break;
        if (read == 0) continue;
        awaitReady(outbound, response, monitor);
        outbound.onNext(
            StageAgentBrowserFileRequest.newBuilder()
                .setUploadId(request.uploadId())
                .setTenantId(request.tenantId())
                .setSessionId(request.sessionId())
                .setCoordinatorTerm(request.coordinatorTerm())
                .setContextEpoch(request.contextEpoch())
                .setFilename(request.filename())
                .setMimeType(request.mimeType())
                .setContentSha256(request.contentSha256())
                .setContentBytes(request.contentBytes())
                .setOffset(offset)
                .setData(ByteString.copyFrom(buffer, 0, read))
                .build());
        digest.update(buffer, 0, read);
        offset = Math.addExact(offset, read);
        if (offset > request.contentBytes()) {
          outbound.onError(new IllegalArgumentException("File exceeds declared size"));
          throw new StageRejectedException("AGENT_FILE_SIZE_MISMATCH");
        }
      }
      var observedHash = HexFormat.of().formatHex(digest.digest());
      if (offset != request.contentBytes()
          || !observedHash.equalsIgnoreCase(request.contentSha256())) {
        outbound.onError(new IllegalArgumentException("File integrity mismatch"));
        throw new StageRejectedException("AGENT_FILE_INTEGRITY_MISMATCH");
      }
      outbound.onCompleted();
      var result = response.get(deadlineSeconds, TimeUnit.SECONDS);
      if (!request.uploadId().equals(result.getUploadId())
          || !request.nodeId().equals(result.getNodeId())
          || !request.sessionId().equals(result.getSessionId())
          || !request.contentSha256().equalsIgnoreCase(result.getContentSha256())
          || request.contentBytes() != result.getContentBytes()) {
        throw new StageRejectedException("AGENT_FILE_NODE_RESPONSE_MISMATCH");
      }
      return new StageResult(
          result.getUploadId(),
          result.getNodeId(),
          result.getSessionId(),
          result.getContentSha256(),
          result.getContentBytes());
    } catch (IOException exception) {
      outbound.onError(exception);
      throw new StageRejectedException("AGENT_FILE_STREAM_FAILED", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new StageUnavailableException("AGENT_FILE_STAGE_INTERRUPTED", exception);
    } catch (ExecutionException exception) {
      var cause = exception.getCause();
      if (cause instanceof StatusRuntimeException status
          && switch (status.getStatus().getCode()) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION, RESOURCE_EXHAUSTED, ALREADY_EXISTS -> true;
            default -> false;
          }) {
        throw new StageRejectedException("AGENT_FILE_STAGE_REJECTED", cause);
      }
      throw new StageUnavailableException("AGENT_FILE_NODE_FAILED", exception);
    } catch (TimeoutException exception) {
      throw new StageUnavailableException("AGENT_FILE_NODE_TIMEOUT", exception);
    }
  }

  private static ClientCallStreamObserver<StageAgentBrowserFileRequest> awaitStream(
      AtomicReference<ClientCallStreamObserver<StageAgentBrowserFileRequest>> stream,
      CompletableFuture<?> response,
      Object monitor) {
    for (var attempt = 0; attempt < 100; attempt++) {
      if (stream.get() != null) return stream.get();
      if (response.isCompletedExceptionally()) {
        throw new StageUnavailableException("AGENT_FILE_NODE_FAILED", null);
      }
      waitForSignal(monitor, 50);
    }
    throw new StageUnavailableException("AGENT_FILE_NODE_TIMEOUT", null);
  }

  private static void awaitReady(
      ClientCallStreamObserver<StageAgentBrowserFileRequest> stream,
      CompletableFuture<?> response,
      Object monitor) {
    while (!stream.isReady()) {
      if (response.isDone()) throw new StageUnavailableException("AGENT_FILE_NODE_FAILED", null);
      waitForSignal(monitor, 250);
    }
  }

  private static void waitForSignal(Object monitor, long millis) {
    synchronized (monitor) {
      try {
        monitor.wait(millis);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new StageUnavailableException("AGENT_FILE_STAGE_INTERRUPTED", exception);
      }
    }
  }

  @PreDestroy
  void closeChannels() {
    channels.values().forEach(ManagedChannel::shutdown);
    channels.clear();
  }
}
