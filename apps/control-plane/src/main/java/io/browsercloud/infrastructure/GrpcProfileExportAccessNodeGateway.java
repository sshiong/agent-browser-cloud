package io.browsercloud.infrastructure;

import static io.browsercloud.application.ProfileExportAccessNodeGateway.*;

import io.browsercloud.application.ProfileExportAccessNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.PresignProfileExportDownloadRequest;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GrpcProfileExportAccessNodeGateway implements ProfileExportAccessNodeGateway {

  private final BrowserNodeJpaRepository nodes;
  private final GrpcTransportFactory transportFactory;
  private final boolean production;

  public GrpcProfileExportAccessNodeGateway(
      BrowserNodeJpaRepository nodes,
      GrpcTransportFactory transportFactory,
      @Value("${app.environment:local}") String environment) {
    this.nodes = nodes;
    this.transportFactory = transportFactory;
    this.production = "production".equalsIgnoreCase(environment);
  }

  @Override
  public SignedProfileExport sign(SignProfileExportRequest request) {
    var node =
        nodes
            .findById(request.nodeId())
            .filter(candidate -> candidate.isReadyForDispatch())
            .orElseThrow(
                () -> new ProfileExportNodeUnavailableException("PROFILE_EXPORT_NODE_UNAVAILABLE"));
    var channel = transportFactory.nodeChannel(node.getGrpcTarget());
    try {
      var response =
          NodeControlServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(15, TimeUnit.SECONDS)
              .presignProfileExportDownload(
                  PresignProfileExportDownloadRequest.newBuilder()
                      .setGrantId(request.grantId())
                      .setTenantId(request.tenantId())
                      .setProfileId(request.profileId())
                      .setCheckpointId(request.checkpointId())
                      .setExpiresInSeconds(request.expiresInSeconds())
                      .build());
      if (!request.grantId().equals(response.getGrantId())
          || !request.nodeId().equals(response.getNodeId())
          || !request.profileId().equals(response.getProfileId())
          || !request.checkpointId().equals(response.getCheckpointId())
          || response.getArchiveSizeBytes() <= 0
          || !response.getArchiveSha256().matches("^[0-9a-f]{64}$")
          || response.getExpiresAtMs() <= Instant.now().toEpochMilli()) {
        throw new ProfileExportNodeRejectedException("PROFILE_EXPORT_RESPONSE_INVALID");
      }
      validateDownloadUrl(response.getDownloadUrl());
      return new SignedProfileExport(
          response.getGrantId(),
          response.getNodeId(),
          response.getProfileId(),
          response.getCheckpointId(),
          response.getArchiveSha256(),
          response.getArchiveSizeBytes(),
          response.getDownloadUrl(),
          Instant.ofEpochMilli(response.getExpiresAtMs()));
    } catch (StatusRuntimeException exception) {
      throw mapNodeFailure(exception);
    } finally {
      channel.shutdown();
    }
  }

  static RuntimeException mapNodeFailure(StatusRuntimeException exception) {
    return switch (exception.getStatus().getCode()) {
      case INVALID_ARGUMENT, FAILED_PRECONDITION, RESOURCE_EXHAUSTED ->
          new ProfileExportNodeRejectedException("PROFILE_EXPORT_ARCHIVE_REJECTED", exception);
      default -> new ProfileExportNodeUnavailableException("PROFILE_EXPORT_NODE_FAILED", exception);
    };
  }

  private void validateDownloadUrl(String value) {
    try {
      if (value == null || value.isBlank() || value.length() > 8192) {
        throw new IllegalArgumentException("empty or oversized URL");
      }
      var uri = URI.create(value);
      var local =
          "localhost".equalsIgnoreCase(uri.getHost())
              || "127.0.0.1".equals(uri.getHost())
              || "::1".equals(uri.getHost())
              || (uri.getHost() != null && uri.getHost().endsWith(".local"));
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || (!"https".equalsIgnoreCase(uri.getScheme())
              && !(local && "http".equalsIgnoreCase(uri.getScheme())))
          || (production && !"https".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException("unsafe signed URL");
      }
    } catch (IllegalArgumentException exception) {
      throw new ProfileExportNodeRejectedException("PROFILE_EXPORT_RESPONSE_INVALID", exception);
    }
  }
}
