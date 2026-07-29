package io.browsercloud.infrastructure;

import com.google.protobuf.ByteString;
import io.browsercloud.application.ProfileImportNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.UploadProfileImportRequest;
import io.browsercloud.proto.node.v1.UploadProfileImportResponse;
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

/** Direct streaming data plane for Profile imports; archive bytes never touch PostgreSQL. */
@Component
public class GrpcProfileImportNodeGateway implements ProfileImportNodeGateway {

  private static final int CHUNK_BYTES = 256 * 1024;
  private static final long NODE_FRESHNESS_SECONDS = 45;

  private final BrowserNodeJpaRepository nodes;
  private final GrpcTransportFactory transportFactory;
  private final Map<String, ManagedChannel> channels =
      new java.util.concurrent.ConcurrentHashMap<>();

  public GrpcProfileImportNodeGateway(
      BrowserNodeJpaRepository nodes, GrpcTransportFactory transportFactory) {
    this.nodes = nodes;
    this.transportFactory = transportFactory;
  }

  @Override
  public ProfileImportNodeResult upload(ProfileImportNodeRequest request, InputStream archive) {
    var node =
        nodes
            .findProfileImportCandidates(Instant.now().minusSeconds(NODE_FRESHNESS_SECONDS))
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new ProfileImportNodeUnavailableException("PROFILE_IMPORT_NODE_UNAVAILABLE"));
    var channel =
        channels.compute(
            node.getNodeId(),
            (ignored, existing) -> {
              if (existing != null && !existing.isShutdown()) return existing;
              return transportFactory.nodeChannel(node.getGrpcTarget());
            });
    var deadlineSeconds =
        Math.min(300L, Math.max(30L, 30L + request.archiveSizeBytes() / (2 * 1024 * 1024)));
    var response = new CompletableFuture<UploadProfileImportResponse>();
    var requestStream = new AtomicReference<ClientCallStreamObserver<UploadProfileImportRequest>>();
    var readinessMonitor = new Object();
    var responseObserver =
        new ClientResponseObserver<UploadProfileImportRequest, UploadProfileImportResponse>() {
          @Override
          public void beforeStart(ClientCallStreamObserver<UploadProfileImportRequest> stream) {
            requestStream.set(stream);
            stream.setOnReadyHandler(
                () -> {
                  synchronized (readinessMonitor) {
                    readinessMonitor.notifyAll();
                  }
                });
          }

          @Override
          public void onNext(UploadProfileImportResponse value) {
            response.complete(value);
          }

          @Override
          public void onError(Throwable failure) {
            response.completeExceptionally(failure);
            synchronized (readinessMonitor) {
              readinessMonitor.notifyAll();
            }
          }

          @Override
          public void onCompleted() {
            if (!response.isDone()) {
              response.completeExceptionally(
                  new IllegalStateException("Browser Node omitted Profile Import response"));
            }
          }
        };
    NodeControlServiceGrpc.newStub(channel)
        .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
        .uploadProfileImport(responseObserver);
    var outbound = awaitRequestStream(requestStream, response, readinessMonitor);
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var buffer = new byte[CHUNK_BYTES];
      long offset = 0;
      while (true) {
        int read = archive.read(buffer);
        if (read < 0) break;
        if (read == 0) continue;
        awaitReady(outbound, response, readinessMonitor);
        var bytes = ByteString.copyFrom(buffer, 0, read);
        outbound.onNext(
            UploadProfileImportRequest.newBuilder()
                .setImportId(request.importId())
                .setTenantId(request.tenantId())
                .setProfileId(request.profileId())
                .setCheckpointId(request.checkpointId())
                .setRuntimeBuildId(request.runtimeBuildId())
                .setArchiveSha256(request.archiveSha256())
                .setArchiveSizeBytes(request.archiveSizeBytes())
                .setOffset(offset)
                .setData(bytes)
                .build());
        digest.update(buffer, 0, read);
        offset = Math.addExact(offset, read);
        if (offset > request.archiveSizeBytes()) {
          outbound.onError(new IllegalArgumentException("Profile Import exceeds declared size"));
          throw new ProfileImportNodeRejectedException("PROFILE_IMPORT_SIZE_MISMATCH");
        }
      }
      var observedHash = HexFormat.of().formatHex(digest.digest());
      if (offset != request.archiveSizeBytes()
          || !observedHash.equalsIgnoreCase(request.archiveSha256())) {
        outbound.onError(new IllegalArgumentException("Profile Import integrity mismatch"));
        throw new ProfileImportNodeRejectedException("PROFILE_IMPORT_INTEGRITY_MISMATCH");
      }
      outbound.onCompleted();
      var result = response.get(deadlineSeconds, TimeUnit.SECONDS);
      validateResponse(request, node.getNodeId(), result);
      return new ProfileImportNodeResult(
          result.getImportId(),
          result.getNodeId(),
          result.getProfileId(),
          result.getCheckpointId(),
          result.getCheckpointEpoch(),
          result.getProfileWriteEpoch(),
          result.getCoreSizeBytes(),
          result.getCheckpointFileCount(),
          result.getArchiveSha256(),
          result.getArchiveSizeBytes());
    } catch (IOException exception) {
      outbound.onError(exception);
      throw new ProfileImportNodeRejectedException("PROFILE_IMPORT_STREAM_FAILED", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ProfileImportNodeUnavailableException("PROFILE_IMPORT_INTERRUPTED", exception);
    } catch (ExecutionException exception) {
      throw mapNodeFailure(exception);
    } catch (TimeoutException exception) {
      throw new ProfileImportNodeUnavailableException("PROFILE_IMPORT_NODE_FAILED", exception);
    }
  }

  static RuntimeException mapNodeFailure(ExecutionException exception) {
    if (exception.getCause() instanceof StatusRuntimeException statusFailure) {
      return switch (statusFailure.getStatus().getCode()) {
        case INVALID_ARGUMENT, FAILED_PRECONDITION, RESOURCE_EXHAUSTED ->
            new ProfileImportNodeRejectedException(
                "PROFILE_IMPORT_ARCHIVE_REJECTED", statusFailure);
        default ->
            new ProfileImportNodeUnavailableException("PROFILE_IMPORT_NODE_FAILED", statusFailure);
      };
    }
    return new ProfileImportNodeUnavailableException("PROFILE_IMPORT_NODE_FAILED", exception);
  }

  private static ClientCallStreamObserver<UploadProfileImportRequest> awaitRequestStream(
      AtomicReference<ClientCallStreamObserver<UploadProfileImportRequest>> stream,
      CompletableFuture<?> response,
      Object monitor) {
    for (int attempt = 0; attempt < 100; attempt++) {
      var value = stream.get();
      if (value != null) return value;
      if (response.isCompletedExceptionally()) {
        throw new ProfileImportNodeUnavailableException("PROFILE_IMPORT_NODE_FAILED");
      }
      synchronized (monitor) {
        try {
          monitor.wait(50);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new ProfileImportNodeUnavailableException("PROFILE_IMPORT_INTERRUPTED", exception);
        }
      }
    }
    throw new ProfileImportNodeUnavailableException("PROFILE_IMPORT_NODE_TIMEOUT");
  }

  private static void awaitReady(
      ClientCallStreamObserver<UploadProfileImportRequest> stream,
      CompletableFuture<?> response,
      Object monitor) {
    while (!stream.isReady()) {
      if (response.isDone()) {
        throw new ProfileImportNodeUnavailableException("PROFILE_IMPORT_NODE_FAILED");
      }
      synchronized (monitor) {
        try {
          monitor.wait(250);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new ProfileImportNodeUnavailableException("PROFILE_IMPORT_INTERRUPTED", exception);
        }
      }
    }
  }

  private static void validateResponse(
      ProfileImportNodeRequest request,
      String selectedNodeId,
      UploadProfileImportResponse response) {
    if (!request.importId().equals(response.getImportId())
        || !selectedNodeId.equals(response.getNodeId())
        || !request.profileId().equals(response.getProfileId())
        || !request.checkpointId().equals(response.getCheckpointId())
        || !request.archiveSha256().equalsIgnoreCase(response.getArchiveSha256())
        || request.archiveSizeBytes() != response.getArchiveSizeBytes()
        || response.getCheckpointEpoch() < 1
        || response.getCoreSizeBytes() < 1
        || response.getCheckpointFileCount() < 1) {
      throw new ProfileImportNodeRejectedException("PROFILE_IMPORT_NODE_RESPONSE_MISMATCH");
    }
  }

  @PreDestroy
  void closeChannels() {
    channels.values().forEach(ManagedChannel::shutdown);
    channels.clear();
  }

  public static final class ProfileImportNodeUnavailableException extends RuntimeException {
    public ProfileImportNodeUnavailableException(String code) {
      super(code);
    }

    public ProfileImportNodeUnavailableException(String code, Throwable cause) {
      super(code, cause);
    }
  }

  public static final class ProfileImportNodeRejectedException extends RuntimeException {
    public ProfileImportNodeRejectedException(String code) {
      super(code);
    }

    public ProfileImportNodeRejectedException(String code, Throwable cause) {
      super(code, cause);
    }
  }
}
