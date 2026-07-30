package io.browsercloud.infrastructure;

import static io.browsercloud.application.SessionEvidenceAccessNodeGateway.*;

import io.browsercloud.application.SessionEvidenceAccessNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Calls the current Browser Node over the internal mTLS control channel. Signed URLs are validated
 * and returned directly to the caller; they are never logged or stored.
 */
@Component
public class GrpcSessionEvidenceAccessNodeGateway implements SessionEvidenceAccessNodeGateway {

  private final BrowserNodeJpaRepository nodes;
  private final GrpcTransportFactory transportFactory;
  private final boolean production;

  public GrpcSessionEvidenceAccessNodeGateway(
      BrowserNodeJpaRepository nodes,
      GrpcTransportFactory transportFactory,
      @Value("${app.environment:local}") String environment) {
    this.nodes = nodes;
    this.transportFactory = transportFactory;
    this.production = "production".equalsIgnoreCase(environment);
  }

  @Override
  public SignedEvidenceAccess sign(SignEvidenceAccessRequest request) {
    var node =
        nodes
            .findById(request.nodeId())
            .filter(candidate -> candidate.isReadyForDispatch())
            .orElseThrow(
                () ->
                    new EvidenceAccessNodeUnavailableException("EVIDENCE_ACCESS_NODE_UNAVAILABLE"));
    var channel = transportFactory.nodeChannel(node.getGrpcTarget());
    try {
      var response =
          NodeControlServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .presignEvidenceDownload(
                  PresignEvidenceDownloadRequest.newBuilder()
                      .setGrantId(request.grantId())
                      .setTenantId(request.tenantId())
                      .setProfileId(request.profileId())
                      .setSessionId(request.sessionId())
                      .setEvidenceId(request.evidenceId())
                      .setContentSha256(request.contentSha256())
                      .setContentBytes(request.contentBytes())
                      .setExpiresInSeconds(request.expiresInSeconds())
                      .build());
      if (!request.grantId().equals(response.getGrantId())
          || !request.nodeId().equals(response.getNodeId())
          || !request.evidenceId().equals(response.getEvidenceId())
          || response.getExpiresAtMs() <= Instant.now().toEpochMilli()) {
        throw new EvidenceAccessNodeRejectedException("EVIDENCE_ACCESS_RESPONSE_INVALID");
      }
      validateDownloadUrl(response.getDownloadUrl());
      return new SignedEvidenceAccess(
          response.getGrantId(),
          response.getNodeId(),
          response.getEvidenceId(),
          response.getDownloadUrl(),
          Instant.ofEpochMilli(response.getExpiresAtMs()));
    } catch (StatusRuntimeException exception) {
      throw new EvidenceAccessNodeUnavailableException("EVIDENCE_ACCESS_NODE_FAILED", exception);
    } finally {
      channel.shutdown();
    }
  }

  private void validateDownloadUrl(String value) {
    try {
      if (value == null || value.isBlank() || value.length() > 8192) {
        throw new IllegalArgumentException("empty or oversized URL");
      }
      var uri = URI.create(value);
      var scheme = uri.getScheme();
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || (!"https".equalsIgnoreCase(scheme)
              && !(isLocalHttpHost(uri.getHost()) && "http".equalsIgnoreCase(scheme)))) {
        throw new IllegalArgumentException("unsafe signed URL");
      }
      if (production && !"https".equalsIgnoreCase(scheme)) {
        throw new IllegalArgumentException("production signed URL must use HTTPS");
      }
    } catch (IllegalArgumentException exception) {
      throw new EvidenceAccessNodeRejectedException("EVIDENCE_ACCESS_RESPONSE_INVALID", exception);
    }
  }

  private static boolean isLocalHttpHost(String host) {
    return "localhost".equalsIgnoreCase(host)
        || "127.0.0.1".equals(host)
        || "::1".equals(host)
        || host.endsWith(".local");
  }
}
