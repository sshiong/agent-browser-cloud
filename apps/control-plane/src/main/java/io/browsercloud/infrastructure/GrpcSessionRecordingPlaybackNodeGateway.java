package io.browsercloud.infrastructure;

import static io.browsercloud.application.SessionRecordingPlaybackNodeGateway.*;

import io.browsercloud.application.SessionRecordingPlaybackNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.PresignRecordingPlaybackRequest;
import io.browsercloud.security.DeploymentEnvironment;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Signs bounded recording segment pages over the internal mTLS Node channel. */
@Component
public class GrpcSessionRecordingPlaybackNodeGateway
    implements SessionRecordingPlaybackNodeGateway {

  private final BrowserNodeJpaRepository nodes;
  private final GrpcTransportFactory transportFactory;
  private final boolean production;

  public GrpcSessionRecordingPlaybackNodeGateway(
      BrowserNodeJpaRepository nodes,
      GrpcTransportFactory transportFactory,
      @Value("${app.environment:local}") String environment) {
    this.nodes = nodes;
    this.transportFactory = transportFactory;
    this.production = DeploymentEnvironment.requiresProductionSecurity(environment);
  }

  @Override
  public SignedRecordingPlayback sign(SignRecordingPlaybackRequest request) {
    var node =
        nodes
            .findById(request.nodeId())
            .filter(candidate -> candidate.isReadyForDispatch())
            .orElseThrow(
                () ->
                    new RecordingPlaybackNodeUnavailableException(
                        "RECORDING_PLAYBACK_NODE_UNAVAILABLE"));
    var channel = transportFactory.nodeChannel(node.getGrpcTarget());
    try {
      var response =
          NodeControlServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(15, TimeUnit.SECONDS)
              .presignRecordingPlayback(
                  PresignRecordingPlaybackRequest.newBuilder()
                      .setGrantId(request.grantId())
                      .setTenantId(request.tenantId())
                      .setProfileId(request.profileId())
                      .setSessionId(request.sessionId())
                      .setRecordingId(request.recordingId())
                      .setManifestSha256(request.manifestSha256())
                      .setManifestBytes(request.manifestBytes())
                      .setSegmentCount(request.segmentCount())
                      .setFrameCount(request.frameCount())
                      .setRedactedFrameCount(request.redactedFrameCount())
                      .setRedactedRegionCount(request.redactedRegionCount())
                      .setRedactionPolicyVersion(request.redactionPolicyVersion())
                      .setStartedAtMs(request.startedAtMs())
                      .setEndedAtMs(request.endedAtMs())
                      .setSegmentOffset(request.segmentOffset())
                      .setSegmentLimit(request.segmentLimit())
                      .setExpiresInSeconds(request.expiresInSeconds())
                      .build());
      if (!request.grantId().equals(response.getGrantId())
          || !request.nodeId().equals(response.getNodeId())
          || !request.recordingId().equals(response.getRecordingId())
          || !request.manifestSha256().equalsIgnoreCase(response.getManifestSha256())
          || request.frameCount() != response.getFrameCount()
          || request.redactedFrameCount() != response.getRedactedFrameCount()
          || request.redactedRegionCount() != response.getRedactedRegionCount()
          || request.redactionPolicyVersion() != response.getRedactionPolicyVersion()
          || response.getExpiresAtMs() <= Instant.now().toEpochMilli()
          || response.getSegmentsCount() > request.segmentLimit()) {
        throw new RecordingPlaybackNodeRejectedException("RECORDING_PLAYBACK_RESPONSE_INVALID");
      }
      long expectedSequence = request.segmentOffset();
      for (var segment : response.getSegmentsList()) {
        if (segment.getSequence() != expectedSequence
            || segment.getContentSha256().length() != 64
            || segment.getContentBytes() <= 0
            || segment.getEndedAtMs() < segment.getStartedAtMs()) {
          throw new RecordingPlaybackNodeRejectedException("RECORDING_PLAYBACK_RESPONSE_INVALID");
        }
        validateDownloadUrl(segment.getDownloadUrl());
        expectedSequence++;
      }
      Long nextOffset = null;
      if (!response.getComplete()) {
        if (response.getNextSegmentOffset() != expectedSequence
            || response.getNextSegmentOffset() <= request.segmentOffset()
            || response.getNextSegmentOffset() >= request.segmentCount()) {
          throw new RecordingPlaybackNodeRejectedException("RECORDING_PLAYBACK_RESPONSE_INVALID");
        }
        nextOffset = response.getNextSegmentOffset();
      } else if (expectedSequence != request.segmentCount()) {
        throw new RecordingPlaybackNodeRejectedException("RECORDING_PLAYBACK_RESPONSE_INVALID");
      }
      return new SignedRecordingPlayback(
          response.getGrantId(),
          response.getNodeId(),
          response.getRecordingId(),
          response.getManifestSha256(),
          response.getFrameCount(),
          response.getRedactedFrameCount(),
          response.getRedactedRegionCount(),
          response.getRedactionPolicyVersion(),
          Instant.ofEpochMilli(response.getExpiresAtMs()),
          nextOffset,
          response.getSegmentsList().stream()
              .map(
                  segment ->
                      new SignedRecordingSegment(
                          segment.getSequence(),
                          segment.getContentSha256(),
                          segment.getContentBytes(),
                          segment.getFrameCount(),
                          segment.getStartedAtMs(),
                          segment.getEndedAtMs(),
                          segment.getDownloadUrl()))
              .toList());
    } catch (StatusRuntimeException exception) {
      throw new RecordingPlaybackNodeUnavailableException(
          "RECORDING_PLAYBACK_NODE_FAILED", exception);
    } finally {
      channel.shutdown();
    }
  }

  private void validateDownloadUrl(String value) {
    try {
      if (value == null || value.isBlank() || value.length() > 2048) {
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
      throw new RecordingPlaybackNodeRejectedException(
          "RECORDING_PLAYBACK_RESPONSE_INVALID", exception);
    }
  }

  private static boolean isLocalHttpHost(String host) {
    return "localhost".equalsIgnoreCase(host)
        || "127.0.0.1".equals(host)
        || "::1".equals(host)
        || host.endsWith(".local");
  }
}
