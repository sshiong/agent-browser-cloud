package io.browsercloud.infrastructure;

import static io.browsercloud.application.RecordingLegalHoldNodeGateway.*;

import io.browsercloud.application.BrowserCapacityApplicationService;
import io.browsercloud.application.RecordingLegalHoldNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.ApplyRecordingLegalHoldRequest;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.RecordingLegalHoldMode;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Uses the existing production mTLS transport; never retries an uncertain remote mutation. */
@Component
public class GrpcRecordingLegalHoldNodeGateway implements RecordingLegalHoldNodeGateway {
  private final BrowserNodeJpaRepository nodes;
  private final BrowserCapacityApplicationService capacity;
  private final GrpcTransportFactory transportFactory;

  public GrpcRecordingLegalHoldNodeGateway(
      BrowserNodeJpaRepository nodes,
      BrowserCapacityApplicationService capacity,
      GrpcTransportFactory transportFactory) {
    this.nodes = nodes;
    this.capacity = capacity;
    this.transportFactory = transportFactory;
  }

  @Override
  public HoldProof apply(HoldRequest request) {
    validate(request);
    if (!capacity.nodeHasCapability(
        request.nodeId(), "recordingLegalHold", "versioned-cas-journal-v1")) {
      throw new HoldNotDispatchedException("RECORDING_HOLD_CAPABILITY_UNAVAILABLE");
    }
    var node =
        nodes
            .findById(request.nodeId())
            .filter(
                candidate ->
                    candidate.isReadyForDispatch()
                        && candidate.getLastHeartbeatAt().isAfter(Instant.now().minusSeconds(30)))
            .orElseThrow(() -> new HoldNotDispatchedException("RECORDING_HOLD_NODE_UNAVAILABLE"));
    // Channel construction precedes invocation. No caller may interpret this preflight as
    // evidence that a previous, durable attempt did not dispatch.
    var channel = transportFactory.nodeChannel(node.getGrpcTarget());
    try {
      var mode =
          request.mode() == HoldMode.ON
              ? RecordingLegalHoldMode.RECORDING_LEGAL_HOLD_MODE_ON
              : RecordingLegalHoldMode.RECORDING_LEGAL_HOLD_MODE_OFF;
      var response =
          NodeControlServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(request.executionBudgetMillis() + 5_000, TimeUnit.MILLISECONDS)
              .applyRecordingLegalHold(
                  ApplyRecordingLegalHoldRequest.newBuilder()
                      .setOperationId(request.operationId())
                      .setAuthorityEpoch(request.authorityEpoch())
                      .setTenantId(request.tenantId())
                      .setProfileId(request.profileId())
                      .setSessionId(request.sessionId())
                      .setRecordingId(request.recordingId())
                      .setManifestSha256(request.manifestSha256())
                      .setManifestBytes(request.manifestBytes())
                      .setSegmentCount(request.segmentCount())
                      .setMode(mode)
                      .setPreviousProofHash(
                          request.previousProofHash() == null ? "" : request.previousProofHash())
                      .setExecutionBudgetMillis(request.executionBudgetMillis())
                      .build());
      var expected = request.segmentCount() * 2 + 1;
      if (!request.operationId().equals(response.getOperationId())
          || request.authorityEpoch() != response.getAuthorityEpoch()
          || !request.nodeId().equals(response.getNodeId())
          || !request.recordingId().equals(response.getRecordingId())
          || mode != response.getMode()
          || !hash(response.getProofHash())
          || !hash(response.getInventorySha256())
          || response.getObjectVersionCount() < expected
          || response.getObjectVersionCount() > expected * 2
          || response.getCompletedAtMs() <= 0
          || response.getCompletedAtMs() > Instant.now().plusSeconds(30).toEpochMilli()) {
        throw new HoldResultUnknownException("RECORDING_HOLD_RESPONSE_INVALID");
      }
      return new HoldProof(
          request.operationId(),
          request.authorityEpoch(),
          request.nodeId(),
          request.recordingId(),
          request.mode(),
          response.getProofHash(),
          response.getInventorySha256(),
          response.getObjectVersionCount(),
          Instant.ofEpochMilli(response.getCompletedAtMs()));
    } catch (StatusRuntimeException exception) {
      // Even a server rejection can follow a partial version update or PREPARED journal.
      // Do not retain server descriptions, object coordinates or credential-bearing causes.
      throw new HoldResultUnknownException("RECORDING_HOLD_REMOTE_RESULT_UNKNOWN");
    } finally {
      channel.shutdown();
    }
  }

  private static void validate(HoldRequest request) {
    if (request == null
        || !id(request.operationId())
        || !request.operationId().startsWith("rhj_")
        || request.authorityEpoch() <= 0
        || !id(request.nodeId())
        || !id(request.tenantId())
        || !id(request.profileId())
        || !id(request.sessionId())
        || !request.sessionId().startsWith("ses_")
        || !id(request.recordingId())
        || !request.recordingId().startsWith("rec_")
        || !hash(request.manifestSha256())
        || request.manifestBytes() <= 0
        || request.segmentCount() <= 0
        || request.segmentCount() > 100_000
        || request.mode() == null
        || (request.previousProofHash() != null && !hash(request.previousProofHash()))
        || (request.mode() == HoldMode.OFF && request.previousProofHash() == null)
        || request.executionBudgetMillis() < 1_000
        || request.executionBudgetMillis() > 120_000) {
      throw new HoldNotDispatchedException("RECORDING_HOLD_REQUEST_INVALID");
    }
  }

  private static boolean id(String value) {
    return value != null && value.matches("^[A-Za-z0-9_-]{1,128}$");
  }

  private static boolean hash(String value) {
    return value != null && value.matches("^[0-9a-f]{64}$");
  }
}
