package io.browsercloud.infrastructure;

import static io.browsercloud.application.RecordingRetentionDeletionNodeGateway.*;

import io.browsercloud.application.RecordingRetentionDeletionNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.DeleteRecordingObjectsRequest;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Executes verified Recording prefix deletion on the Node that committed the manifest. */
@Component
public class GrpcRecordingRetentionDeletionNodeGateway
    implements RecordingRetentionDeletionNodeGateway {

  private final BrowserNodeJpaRepository nodes;
  private final GrpcTransportFactory transportFactory;

  public GrpcRecordingRetentionDeletionNodeGateway(
      BrowserNodeJpaRepository nodes, GrpcTransportFactory transportFactory) {
    this.nodes = nodes;
    this.transportFactory = transportFactory;
  }

  @Override
  public RecordingDeletionProof delete(RecordingDeletionRequest request) {
    var node =
        nodes
            .findById(request.nodeId())
            .filter(candidate -> candidate.isReadyForDispatch())
            .orElseThrow(
                () ->
                    new RecordingDeletionNodeUnavailableException(
                        "RECORDING_DELETION_NODE_UNAVAILABLE"));
    var channel = transportFactory.nodeChannel(node.getGrpcTarget());
    try {
      var response =
          NodeControlServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(45, TimeUnit.SECONDS)
              .deleteRecordingObjects(
                  DeleteRecordingObjectsRequest.newBuilder()
                      .setDeletionJobId(request.jobId())
                      .setDeletionEpoch(request.deletionEpoch())
                      .setTenantId(request.tenantId())
                      .setProfileId(request.profileId())
                      .setSessionId(request.sessionId())
                      .setRecordingId(request.recordingId())
                      .setManifestSha256(request.manifestSha256())
                      .setManifestBytes(request.manifestBytes())
                      .setSegmentCount(request.segmentCount())
                      .build());
      var expectedObjects = Math.addExact(Math.multiplyExact(request.segmentCount(), 2), 1);
      if (!request.jobId().equals(response.getDeletionJobId())
          || request.deletionEpoch() != response.getDeletionEpoch()
          || !request.nodeId().equals(response.getNodeId())
          || !request.recordingId().equals(response.getRecordingId())
          || response.getDeletionProofHash().length() != 64
          || !response.getDeletionProofHash().matches("^[0-9a-f]{64}$")
          || response.getDeletedObjectCount() != expectedObjects
          || response.getCompletedAtMs() <= 0
          || response.getCompletedAtMs() > Instant.now().plusSeconds(30).toEpochMilli()) {
        throw new RecordingDeletionNodeRejectedException("RECORDING_DELETION_RESPONSE_INVALID");
      }
      return new RecordingDeletionProof(
          response.getDeletionJobId(),
          response.getDeletionEpoch(),
          response.getNodeId(),
          response.getRecordingId(),
          response.getDeletionProofHash(),
          response.getDeletedObjectCount(),
          Instant.ofEpochMilli(response.getCompletedAtMs()));
    } catch (StatusRuntimeException exception) {
      if (exception.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
          || exception.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
        throw new RecordingDeletionNodeRejectedException(
            "RECORDING_DELETION_OBJECT_REJECTED", exception);
      }
      throw new RecordingDeletionNodeUnavailableException(
          "RECORDING_DELETION_NODE_FAILED", exception);
    } finally {
      channel.shutdown();
    }
  }
}
