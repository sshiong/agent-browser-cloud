package io.browsercloud.infrastructure;

import com.google.protobuf.InvalidProtocolBufferException;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.proto.node.v1.AgentActionFailedEvent;
import io.browsercloud.proto.node.v1.AgentNavigationFailedEvent;
import io.browsercloud.proto.node.v1.BrowserCrashEvent;
import io.browsercloud.proto.node.v1.BrowserStateDiffEvent;
import io.browsercloud.proto.node.v1.BrowserStateEvent;
import io.browsercloud.proto.node.v1.BrowserStateSnapshotBeginEvent;
import io.browsercloud.proto.node.v1.BrowserStateSnapshotChunkEvent;
import io.browsercloud.proto.node.v1.BrowserStateSnapshotCommitEvent;
import io.browsercloud.proto.node.v1.DiffTruncatedEvent;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.HumanAssistFailedEvent;
import io.browsercloud.proto.node.v1.HumanTakeoverEndedEvent;
import io.browsercloud.proto.node.v1.HumanTakeoverReadyEvent;
import io.browsercloud.proto.node.v1.RuntimeResourcesAdjustedEvent;
import io.browsercloud.proto.node.v1.RuntimeStartedEvent;
import io.browsercloud.proto.node.v1.RuntimeStoppedEvent;
import io.browsercloud.proto.node.v1.SessionEvidenceCapturedEvent;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** 将正式 Protobuf EventEnvelope 映射为 Coordinator 命令。 */
@Component
public class NodeEventMapper {

  static final String RUNTIME_STARTED = "RuntimeStarted";
  static final String RUNTIME_STOPPED = "RuntimeStopped";
  static final String RUNTIME_RESOURCES_ADJUSTED = "RuntimeResourcesAdjusted";
  static final String BROWSER_CRASHED = "BrowserCrashed";
  static final String BROWSER_STATE_UPDATED = "BrowserStateUpdated";
  static final String BROWSER_STATE_SNAPSHOT_BEGIN = "BrowserStateSnapshotBegin";
  static final String BROWSER_STATE_SNAPSHOT_CHUNK = "BrowserStateSnapshotChunk";
  static final String BROWSER_STATE_SNAPSHOT_COMMIT = "BrowserStateSnapshotCommit";
  static final String BROWSER_STATE_DIFF = "BrowserStateDiff";
  static final String DIFF_TRUNCATED = "DiffTruncated";
  static final String AGENT_NAVIGATION_FAILED = "AgentNavigationFailed";
  static final String AGENT_ACTION_FAILED = "AgentActionFailed";
  static final String HUMAN_ASSIST_FAILED = "HumanAssistFailed";
  static final String SESSION_EVIDENCE_CAPTURED = "SessionEvidenceCaptured";
  static final String HUMAN_TAKEOVER_READY = "HumanTakeoverReady";
  static final String HUMAN_TAKEOVER_ENDED = "HumanTakeoverEnded";
  private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
  static final int MAX_SNAPSHOT_CHUNK_BYTES = 16 * 1024;
  static final int MAX_SNAPSHOT_CHUNKS = 32;
  static final long MAX_SNAPSHOT_BYTES = (long) MAX_SNAPSHOT_CHUNK_BYTES * MAX_SNAPSHOT_CHUNKS;

  public NodeEventReceived toCommand(EventEnvelope envelope) {
    requireText(envelope.getEventId(), "event_id");
    requireText(envelope.getTenantId(), "tenant_id");
    requireText(envelope.getSessionId(), "session_id");
    requireText(envelope.getEventType(), "event_type");
    if (envelope.getSequence() <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    if (envelope.getPayload().size() > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("event payload exceeds 64 KiB");
    }

    var event = parseEvent(envelope);
    if (!envelope.getSessionId().equals(eventSessionId(event))) {
      throw new IllegalArgumentException("payload session_id does not match envelope");
    }
    return new NodeEventReceived(
        envelope.getEventId(),
        envelope.getTenantId(),
        envelope.getSessionId(),
        envelope.getCoordinatorTerm(),
        envelope.getContextEpoch(),
        envelope.getOperationEpoch(),
        envelope.getSequence(),
        event);
  }

  private NodeEvent parseEvent(EventEnvelope envelope) {
    try {
      return switch (envelope.getEventType()) {
        case RUNTIME_STARTED -> {
          var payload = RuntimeStartedEvent.parseFrom(envelope.getPayload());
          if (!payload.getProxyBindingId().isBlank()) {
            requireText(payload.getExitIp(), "exit_ip");
            requireText(payload.getExitCountry(), "exit_country");
            requireText(payload.getExitAsn(), "exit_asn");
          } else if (!payload.getExitIp().isBlank()
              || !payload.getExitCountry().isBlank()
              || !payload.getExitAsn().isBlank()) {
            throw new IllegalArgumentException("direct Runtime cannot report proxy exit metadata");
          }
          yield new NodeEvent.RuntimeStarted(
              payload.getSessionId(),
              payload.getNodeId(),
              payload.getRuntimeBuildId(),
              payload.getPid(),
              payload.getBrowserGeneration(),
              payload.getCdpEndpoint(),
              payload.getProxyBindingId(),
              payload.getExitIp(),
              payload.getExitCountry(),
              payload.getExitAsn());
        }
        case RUNTIME_STOPPED -> {
          var payload = RuntimeStoppedEvent.parseFrom(envelope.getPayload());
          boolean hasCheckpoint = !payload.getCheckpointId().isBlank();
          if (hasCheckpoint) {
            requireText(payload.getProfileId(), "profile_id");
            if (payload.getCheckpointEpoch() <= 0 || payload.getProfileWriteEpoch() <= 0) {
              throw new IllegalArgumentException("profile checkpoint epochs must be positive");
            }
          } else if (!payload.getProfileId().isBlank()
              || payload.getCheckpointEpoch() != 0
              || payload.getProfileWriteEpoch() != 0
              || payload.getCoreSizeBytes() != 0
              || payload.getCheckpointFileCount() != 0) {
            throw new IllegalArgumentException("empty profile checkpoint has metadata");
          }
          if (!payload.getRestoreStatus().equals("EMPTY")
              && !payload.getRestoreStatus().equals("TECHNICAL_READY")) {
            throw new IllegalArgumentException("unsupported profile restore_status");
          }
          yield new NodeEvent.RuntimeStopped(
              payload.getSessionId(),
              payload.getReason(),
              payload.getExitCode(),
              payload.getProfileId(),
              payload.getCheckpointId(),
              payload.getCheckpointEpoch(),
              payload.getProfileWriteEpoch(),
              payload.getCoreSizeBytes(),
              payload.getCheckpointFileCount(),
              payload.getRestoreStatus());
        }
        case RUNTIME_RESOURCES_ADJUSTED -> {
          var payload = RuntimeResourcesAdjustedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getNodeId(), "node_id");
          requireText(payload.getOldResourceClass(), "old_resource_class");
          requireText(payload.getNewResourceClass(), "new_resource_class");
          requireText(payload.getReason(), "reason");
          requireText(payload.getOperationId(), "operation_id");
          if (payload.getOldCpuMillis() <= 0
              || payload.getNewCpuMillis() <= 0
              || payload.getOldMemoryRequestMib() <= 0
              || payload.getNewMemoryRequestMib() <= 0
              || payload.getOldMemoryLimitMib() < payload.getOldMemoryRequestMib()
              || payload.getNewMemoryLimitMib() < payload.getNewMemoryRequestMib()
              || payload.getOldPidLimit() < 32
              || payload.getNewPidLimit() < 32
              || payload.getOldTabBudget() <= 0
              || payload.getNewTabBudget() <= 0) {
            throw new IllegalArgumentException("resource adjustment limits are invalid");
          }
          var oldStateCollectorBudget =
              payload.hasOldStateCollectorBudgetPercent()
                  ? payload.getOldStateCollectorBudgetPercent()
                  : null;
          var oldRemoteDesktopBitrate =
              payload.hasOldRemoteDesktopBitrateKbps()
                  ? payload.getOldRemoteDesktopBitrateKbps()
                  : null;
          var newStateCollectorBudget =
              payload.hasNewStateCollectorBudgetPercent()
                  ? payload.getNewStateCollectorBudgetPercent()
                  : null;
          var newRemoteDesktopBitrate =
              payload.hasNewRemoteDesktopBitrateKbps()
                  ? payload.getNewRemoteDesktopBitrateKbps()
                  : null;
          var oldExtensionCpuWeight =
              payload.hasOldExtensionCpuWeight() ? payload.getOldExtensionCpuWeight() : null;
          var newExtensionCpuWeight =
              payload.hasNewExtensionCpuWeight() ? payload.getNewExtensionCpuWeight() : null;
          var oldMediaEncoderSlots =
              payload.hasOldMediaEncoderSlots() ? payload.getOldMediaEncoderSlots() : null;
          var newMediaEncoderSlots =
              payload.hasNewMediaEncoderSlots() ? payload.getNewMediaEncoderSlots() : null;
          var oldFreezeBackgroundTabs =
              payload.hasOldFreezeBackgroundTabs() ? payload.getOldFreezeBackgroundTabs() : null;
          var newFreezeBackgroundTabs =
              payload.hasNewFreezeBackgroundTabs() ? payload.getNewFreezeBackgroundTabs() : null;
          var oldBlockNewTabs = payload.hasOldBlockNewTabs() ? payload.getOldBlockNewTabs() : null;
          var newBlockNewTabs = payload.hasNewBlockNewTabs() ? payload.getNewBlockNewTabs() : null;
          var oldPausedExtensionIds =
              payload.hasOldExtensionBackgroundPolicy()
                  ? List.copyOf(
                      payload.getOldExtensionBackgroundPolicy().getPausedExtensionIdsList())
                  : null;
          var newPausedExtensionIds =
              payload.hasNewExtensionBackgroundPolicy()
                  ? List.copyOf(
                      payload.getNewExtensionBackgroundPolicy().getPausedExtensionIdsList())
                  : null;
          var oldSuccessTraceSamplePercent =
              payload.hasOldSuccessTraceSamplePercent()
                  ? payload.getOldSuccessTraceSamplePercent()
                  : null;
          var newSuccessTraceSamplePercent =
              payload.hasNewSuccessTraceSamplePercent()
                  ? payload.getNewSuccessTraceSamplePercent()
                  : null;
          var oldObserverFrameRateFps =
              payload.hasOldObserverFrameRateFps() ? payload.getOldObserverFrameRateFps() : null;
          var newObserverFrameRateFps =
              payload.hasNewObserverFrameRateFps() ? payload.getNewObserverFrameRateFps() : null;
          var oldVideoRecordingEnabled =
              payload.hasOldVideoRecordingEnabled() ? payload.getOldVideoRecordingEnabled() : null;
          var newVideoRecordingEnabled =
              payload.hasNewVideoRecordingEnabled() ? payload.getNewVideoRecordingEnabled() : null;
          var oldSuccessScreenshotSamplePercent =
              payload.hasOldSuccessScreenshotSamplePercent()
                  ? payload.getOldSuccessScreenshotSamplePercent()
                  : null;
          var newSuccessScreenshotSamplePercent =
              payload.hasNewSuccessScreenshotSamplePercent()
                  ? payload.getNewSuccessScreenshotSamplePercent()
                  : null;
          if ((oldStateCollectorBudget == null) != (newStateCollectorBudget == null)
              || (oldRemoteDesktopBitrate == null) != (newRemoteDesktopBitrate == null)
              || (oldExtensionCpuWeight == null) != (newExtensionCpuWeight == null)
              || (oldMediaEncoderSlots == null) != (newMediaEncoderSlots == null)
              || (oldFreezeBackgroundTabs == null) != (newFreezeBackgroundTabs == null)
              || (oldBlockNewTabs == null) != (newBlockNewTabs == null)
              || (oldPausedExtensionIds == null) != (newPausedExtensionIds == null)
              || (oldSuccessTraceSamplePercent == null) != (newSuccessTraceSamplePercent == null)
              || (oldObserverFrameRateFps == null) != (newObserverFrameRateFps == null)
              || (oldVideoRecordingEnabled == null) != (newVideoRecordingEnabled == null)
              || (oldSuccessScreenshotSamplePercent == null)
                  != (newSuccessScreenshotSamplePercent == null)
              || !validExtensionPolicy(oldPausedExtensionIds)
              || !validExtensionPolicy(newPausedExtensionIds)
              || (oldSuccessTraceSamplePercent != null
                  && (oldSuccessTraceSamplePercent < 1
                      || oldSuccessTraceSamplePercent > 100
                      || newSuccessTraceSamplePercent < 1
                      || newSuccessTraceSamplePercent > 100))
              || (oldSuccessScreenshotSamplePercent != null
                  && (oldSuccessScreenshotSamplePercent < 1
                      || oldSuccessScreenshotSamplePercent > 100
                      || newSuccessScreenshotSamplePercent < 1
                      || newSuccessScreenshotSamplePercent > 100))
              || (oldObserverFrameRateFps != null
                  && (oldObserverFrameRateFps < 0
                      || oldObserverFrameRateFps > 60
                      || newObserverFrameRateFps < 0
                      || newObserverFrameRateFps > 60))
              || (oldStateCollectorBudget != null
                  && (oldStateCollectorBudget < 10
                      || oldStateCollectorBudget > 100
                      || newStateCollectorBudget < 10
                      || newStateCollectorBudget > 100))
              || (oldRemoteDesktopBitrate != null
                  && (oldRemoteDesktopBitrate < 0
                      || oldRemoteDesktopBitrate > 100_000
                      || newRemoteDesktopBitrate < 0
                      || newRemoteDesktopBitrate > 100_000))
              || (oldExtensionCpuWeight != null
                  && (oldExtensionCpuWeight < 1
                      || oldExtensionCpuWeight > 10_000
                      || newExtensionCpuWeight < 1
                      || newExtensionCpuWeight > 10_000))
              || (oldMediaEncoderSlots != null
                  && (oldMediaEncoderSlots < 0
                      || oldMediaEncoderSlots > 32
                      || newMediaEncoderSlots < 0
                      || newMediaEncoderSlots > 32))) {
            throw new IllegalArgumentException("non-cgroup resource adjustment limits are invalid");
          }
          yield new NodeEvent.RuntimeResourcesAdjusted(
              payload.getSessionId(),
              payload.getNodeId(),
              payload.getOldResourceClass(),
              payload.getOldCpuMillis(),
              payload.getOldMemoryRequestMib(),
              payload.getOldMemoryLimitMib(),
              payload.getOldPidLimit(),
              payload.getOldTabBudget(),
              payload.getNewResourceClass(),
              payload.getNewCpuMillis(),
              payload.getNewMemoryRequestMib(),
              payload.getNewMemoryLimitMib(),
              payload.getNewPidLimit(),
              payload.getNewTabBudget(),
              oldStateCollectorBudget,
              oldRemoteDesktopBitrate,
              newStateCollectorBudget,
              newRemoteDesktopBitrate,
              oldExtensionCpuWeight,
              newExtensionCpuWeight,
              oldMediaEncoderSlots,
              newMediaEncoderSlots,
              oldFreezeBackgroundTabs,
              newFreezeBackgroundTabs,
              oldBlockNewTabs,
              newBlockNewTabs,
              oldPausedExtensionIds,
              newPausedExtensionIds,
              oldSuccessTraceSamplePercent,
              newSuccessTraceSamplePercent,
              oldObserverFrameRateFps,
              newObserverFrameRateFps,
              oldVideoRecordingEnabled,
              newVideoRecordingEnabled,
              oldSuccessScreenshotSamplePercent,
              newSuccessScreenshotSamplePercent,
              payload.getReason(),
              payload.getOperationId());
        }
        case BROWSER_CRASHED -> {
          var payload = BrowserCrashEvent.parseFrom(envelope.getPayload());
          yield new NodeEvent.RuntimeCrashed(
              payload.getSessionId(), payload.getCrashType(), payload.getReason());
        }
        case BROWSER_STATE_UPDATED -> {
          var payload = BrowserStateEvent.parseFrom(envelope.getPayload());
          yield toState(payload);
        }
        case BROWSER_STATE_SNAPSHOT_BEGIN -> {
          var payload = BrowserStateSnapshotBeginEvent.parseFrom(envelope.getPayload());
          validateSnapshotManifest(
              payload.getSessionId(),
              payload.getSnapshotId(),
              payload.getTotalChunks(),
              payload.getTotalBytes(),
              payload.getPayloadSha256());
          if (payload.getStateVersion() <= 0
              || payload.getTargetRevision() <= 0
              || !payload.getSnapshotKind().equals("FULL_RESYNC")) {
            throw new IllegalArgumentException("snapshot Begin state metadata is invalid");
          }
          var collectionCpuMillis =
              payload.hasCollectionCpuMillis() ? payload.getCollectionCpuMillis() : null;
          validateCollectionCpuMillis(collectionCpuMillis);
          yield new NodeEvent.StateSnapshotBegin(
              payload.getSessionId(),
              payload.getSnapshotId(),
              payload.getStateVersion(),
              payload.getTargetRevision(),
              payload.getTotalChunks(),
              payload.getTotalBytes(),
              payload.getPayloadSha256(),
              payload.getSnapshotKind(),
              collectionCpuMillis);
        }
        case BROWSER_STATE_SNAPSHOT_CHUNK -> {
          var payload = BrowserStateSnapshotChunkEvent.parseFrom(envelope.getPayload());
          requireText(payload.getSessionId(), "session_id");
          validateSnapshotId(payload.getSnapshotId());
          if (payload.getTotalChunks() <= 0
              || payload.getTotalChunks() > MAX_SNAPSHOT_CHUNKS
              || payload.getChunkIndex() >= payload.getTotalChunks()
              || payload.getData().isEmpty()
              || payload.getData().size() > MAX_SNAPSHOT_CHUNK_BYTES
              || !payload.getChunkSha256().matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("snapshot Chunk boundary metadata is invalid");
          }
          var observedHash = sha256(payload.getData().toByteArray());
          if (!MessageDigest.isEqual(
              observedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
              payload.getChunkSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("snapshot Chunk checksum does not match");
          }
          yield new NodeEvent.StateSnapshotChunk(
              payload.getSessionId(),
              payload.getSnapshotId(),
              payload.getChunkIndex(),
              payload.getTotalChunks(),
              payload.getData().toByteArray(),
              payload.getChunkSha256());
        }
        case BROWSER_STATE_SNAPSHOT_COMMIT -> {
          var payload = BrowserStateSnapshotCommitEvent.parseFrom(envelope.getPayload());
          validateSnapshotManifest(
              payload.getSessionId(),
              payload.getSnapshotId(),
              payload.getTotalChunks(),
              payload.getTotalBytes(),
              payload.getPayloadSha256());
          yield new NodeEvent.StateSnapshotCommit(
              payload.getSessionId(),
              payload.getSnapshotId(),
              payload.getTotalChunks(),
              payload.getTotalBytes(),
              payload.getPayloadSha256());
        }
        case BROWSER_STATE_DIFF -> {
          var payload = BrowserStateDiffEvent.parseFrom(envelope.getPayload());
          validateStateMetadata(
              payload.getSessionId(),
              payload.getStateVersion(),
              payload.getTargetRevision(),
              payload.getUrl(),
              payload.getTitle(),
              payload.getContentHash(),
              payload.getStateQuality());
          if (payload.getBaseStateVersion() <= 0
              || payload.getStateVersion() <= payload.getBaseStateVersion()) {
            throw new IllegalArgumentException("State Diff versions are invalid");
          }
          if (payload.getUpsertedTargetsCount() + payload.getRemovedTargetRefsCount() > 500) {
            throw new IllegalArgumentException("State Diff target count exceeds 500");
          }
          payload.getRemovedTargetRefsList().forEach(value -> requireText(value, "target_ref"));
          var resyncRequestId =
              regionResyncRequestId(
                  payload.getSnapshotKind(), payload.getResyncRequestId(), envelope.getEventId());
          validateDiffSnapshotMetadata(
              payload.getSnapshotKind(), payload.getRequestedRootRef(), resyncRequestId);
          var collectionCpuMillis =
              payload.hasCollectionCpuMillis() ? payload.getCollectionCpuMillis() : null;
          validateCollectionCpuMillis(collectionCpuMillis);
          var upsertedTargets =
              payload.getUpsertedTargetsList().stream().map(this::target).toList();
          validateReadinessEvidence(
              payload.getDocumentReadyState(), payload.getNetworkQuietMillis());
          yield new NodeEvent.StateDiff(
              payload.getSessionId(),
              payload.getBaseStateVersion(),
              payload.getStateVersion(),
              payload.getTargetRevision(),
              payload.getUrl(),
              payload.getTitle(),
              payload.getContentHash(),
              payload.getStateQuality(),
              payload.getDocumentReadyState(),
              payload.getNetworkQuietMillis(),
              payload.getNetworkEvidenceFresh(),
              upsertedTargets,
              payload.getRemovedTargetRefsList(),
              payload.getSnapshotKind(),
              payload.getRequestedRootRef(),
              resyncRequestId,
              envelope.getPayload().size(),
              collectionCpuMillis);
        }
        case DIFF_TRUNCATED -> {
          var payload = DiffTruncatedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getReason(), "reason");
          requireText(payload.getAffectedRoot(), "affected_root");
          if (!java.util.Set.of("TARGET_LIMIT", "BYTE_LIMIT", "BACKPRESSURE_LIMIT")
                  .contains(payload.getReason())
              || payload.getAffectedRoot().length() > 512
              || payload.getAffectedRoot().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("DiffTruncated boundary metadata is invalid");
          }
          if (payload.getLastGoodStateVersion() <= 0
              || payload.getCurrentStateVersion() <= payload.getLastGoodStateVersion()) {
            throw new IllegalArgumentException("DiffTruncated versions are invalid");
          }
          yield new NodeEvent.DiffTruncated(
              payload.getSessionId(),
              payload.getReason(),
              payload.getLastGoodStateVersion(),
              payload.getCurrentStateVersion(),
              payload.getAffectedRoot(),
              payload.getEstimatedTargets());
        }
        case AGENT_NAVIGATION_FAILED -> {
          var payload = AgentNavigationFailedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getTaskId(), "task_id");
          requireText(payload.getStepId(), "step_id");
          requireText(payload.getErrorCode(), "error_code");
          yield new NodeEvent.AgentNavigationFailed(
              payload.getSessionId(),
              payload.getTaskId(),
              payload.getStepId(),
              payload.getErrorCode());
        }
        case AGENT_ACTION_FAILED -> {
          var payload = AgentActionFailedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getTaskId(), "task_id");
          requireText(payload.getStepId(), "step_id");
          requireText(payload.getToolId(), "tool_id");
          requireText(payload.getErrorCode(), "error_code");
          if (!java.util.Set.of("CLICK_TARGET", "TYPE_TEXT", "SCROLL", "WAIT_FOR")
              .contains(payload.getToolId())) {
            throw new IllegalArgumentException("unsupported Agent Action tool_id");
          }
          yield new NodeEvent.AgentActionFailed(
              payload.getSessionId(),
              payload.getTaskId(),
              payload.getStepId(),
              payload.getToolId(),
              payload.getErrorCode());
        }
        case HUMAN_ASSIST_FAILED -> {
          var payload = HumanAssistFailedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getChallengeEventId(), "challenge_event_id");
          requireText(payload.getIntentId(), "intent_id");
          requireText(payload.getErrorCode(), "error_code");
          if (!payload.getChallengeEventId().matches("^chl_[A-Za-z0-9]{20}$")
              || !payload.getIntentId().matches("^hint_[A-Za-z0-9]{20}$")
              || !payload.getErrorCode().matches("^[A-Z][A-Z0-9_]{2,127}$")) {
            throw new IllegalArgumentException("Human Assist failure metadata is invalid");
          }
          yield new NodeEvent.HumanAssistFailed(
              payload.getSessionId(),
              payload.getChallengeEventId(),
              payload.getIntentId(),
              payload.getErrorCode());
        }
        case SESSION_EVIDENCE_CAPTURED -> {
          var payload = SessionEvidenceCapturedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getEvidenceId(), "evidence_id");
          requireText(payload.getEvidenceKind(), "evidence_kind");
          requireText(payload.getTaskId(), "task_id");
          requireText(payload.getStepId(), "step_id");
          requireText(payload.getCommandId(), "command_id");
          requireText(payload.getResult(), "result");
          if (!java.util.Set.of(
                  "AGENT_ACTION_SUCCESS",
                  "AGENT_ACTION_FAILURE",
                  "AGENT_NAVIGATION_SUCCESS",
                  "AGENT_NAVIGATION_FAILURE",
                  "OBSERVER_MANUAL")
              .contains(payload.getEvidenceKind())) {
            throw new IllegalArgumentException("unsupported evidence_kind");
          }
          if (payload.getCapturedAtMs() <= 0) {
            throw new IllegalArgumentException("captured_at_ms must be positive");
          }
          var redactionState =
              payload.getRedactionState().isBlank()
                  ? "LEGACY_UNVERIFIED"
                  : payload.getRedactionState();
          var redactedRegionCount = payload.getRedactedRegionCount();
          if (redactedRegionCount > 10_000
              || !(redactionState.equals("LEGACY_UNVERIFIED")
                  || redactionState.equals("MASKED")
                  || redactionState.equals("NOT_REQUIRED")
                  || redactionState.equals("FAILED_CLOSED"))) {
            throw new IllegalArgumentException("evidence redaction metadata is invalid");
          }
          if (payload.getResult().equals("COMMITTED")) {
            var objectKey = payload.getObjectKey();
            var tenantEvidenceRoot = "/tenants/" + envelope.getTenantId() + "/profiles/";
            var evidenceSuffix =
                "/sessions/"
                    + payload.getSessionId()
                    + "/evidence/"
                    + payload.getEvidenceId()
                    + "/screenshot.jpeg";
            if (!payload.getContentSha256().matches("^[0-9a-f]{64}$")
                || payload.getContentBytes() <= 0
                || payload.getContentBytes() > 8L * 1024 * 1024
                || objectKey.isBlank()
                || objectKey.contains("..")
                || objectKey.contains("\\")
                || !("/" + objectKey).contains(tenantEvidenceRoot)
                || !("/" + objectKey).endsWith(evidenceSuffix)
                || !payload.getErrorCode().isBlank()
                || !((redactionState.equals("LEGACY_UNVERIFIED") && redactedRegionCount == 0)
                    || (redactionState.equals("MASKED") && redactedRegionCount > 0)
                    || (redactionState.equals("NOT_REQUIRED") && redactedRegionCount == 0))) {
              throw new IllegalArgumentException("committed evidence metadata is invalid");
            }
          } else if (payload.getResult().equals("FAILED")) {
            if (!payload.getContentSha256().isBlank()
                || payload.getContentBytes() != 0
                || !payload.getObjectKey().isBlank()
                || payload.getErrorCode().isBlank()
                || !((redactionState.equals("LEGACY_UNVERIFIED") && redactedRegionCount == 0)
                    || (redactionState.equals("FAILED_CLOSED") && redactedRegionCount == 0))) {
              throw new IllegalArgumentException("failed evidence metadata is invalid");
            }
          } else {
            throw new IllegalArgumentException("unsupported evidence result");
          }
          yield new NodeEvent.EvidenceCaptured(
              payload.getSessionId(),
              payload.getEvidenceId(),
              payload.getEvidenceKind(),
              payload.getTaskId(),
              payload.getStepId(),
              payload.getCommandId(),
              payload.getContentSha256(),
              payload.getContentBytes(),
              payload.getObjectKey(),
              payload.getCapturedAtMs(),
              payload.getMandatory(),
              payload.getResult(),
              payload.getErrorCode(),
              redactionState,
              redactedRegionCount);
        }
        case HUMAN_TAKEOVER_READY -> {
          var payload = HumanTakeoverReadyEvent.parseFrom(envelope.getPayload());
          if (!payload.hasState()) {
            throw new IllegalArgumentException("HumanTakeoverReady state is required");
          }
          var state = toState(payload.getState());
          if (!payload.getSessionId().equals(state.sessionId())) {
            throw new IllegalArgumentException("takeover state session_id does not match payload");
          }
          yield new NodeEvent.HumanTakeoverReady(
              payload.getSessionId(), payload.getUserId(), state);
        }
        case HUMAN_TAKEOVER_ENDED -> {
          var payload = HumanTakeoverEndedEvent.parseFrom(envelope.getPayload());
          if (!payload.hasState()) {
            throw new IllegalArgumentException("HumanTakeoverEnded state is required");
          }
          var state = toState(payload.getState());
          if (!payload.getSessionId().equals(state.sessionId())) {
            throw new IllegalArgumentException("takeover state session_id does not match payload");
          }
          var reason = payload.getReason().isBlank() ? "USER_RELEASE" : payload.getReason();
          if (!reason.equals("USER_RELEASE") && !reason.equals("GATEWAY_DISCONNECT")) {
            throw new IllegalArgumentException("unsupported HumanTakeoverEnded reason");
          }
          yield new NodeEvent.HumanTakeoverEnded(
              payload.getSessionId(), payload.getUserId(), reason, state);
        }
        default ->
            throw new IllegalArgumentException(
                "unsupported event_type: " + envelope.getEventType());
      };
    } catch (InvalidProtocolBufferException exception) {
      throw new IllegalArgumentException("event payload is not valid protobuf", exception);
    }
  }

  private String eventSessionId(NodeEvent event) {
    return switch (event) {
      case NodeEvent.RuntimeStarted started -> started.sessionId();
      case NodeEvent.RuntimeStopped stopped -> stopped.sessionId();
      case NodeEvent.RuntimeResourcesAdjusted adjusted -> adjusted.sessionId();
      case NodeEvent.RuntimeCrashed crashed -> crashed.sessionId();
      case NodeEvent.StateUpdated updated -> updated.sessionId();
      case NodeEvent.StateSnapshotBegin begin -> begin.sessionId();
      case NodeEvent.StateSnapshotChunk chunk -> chunk.sessionId();
      case NodeEvent.StateSnapshotCommit commit -> commit.sessionId();
      case NodeEvent.StateDiff diff -> diff.sessionId();
      case NodeEvent.DiffTruncated truncated -> truncated.sessionId();
      case NodeEvent.AgentNavigationFailed failed -> failed.sessionId();
      case NodeEvent.AgentActionFailed failed -> failed.sessionId();
      case NodeEvent.HumanAssistFailed failed -> failed.sessionId();
      case NodeEvent.EvidenceCaptured captured -> captured.sessionId();
      case NodeEvent.HumanTakeoverReady ready -> ready.sessionId();
      case NodeEvent.HumanTakeoverEnded ended -> ended.sessionId();
    };
  }

  public NodeEvent.StateUpdated toState(BrowserStateEvent payload) {
    validateStateMetadata(
        payload.getSessionId(),
        payload.getStateVersion(),
        payload.getTargetRevision(),
        payload.getUrl(),
        payload.getTitle(),
        payload.getContentHash(),
        payload.getStateQuality());
    if (payload.getTargetsCount() > 500) {
      throw new IllegalArgumentException("Browser State target count exceeds 500");
    }
    var targets = payload.getTargetsList().stream().map(this::target).toList();
    validateReadinessEvidence(payload.getDocumentReadyState(), payload.getNetworkQuietMillis());
    return new NodeEvent.StateUpdated(
        payload.getSessionId(),
        payload.getStateVersion(),
        payload.getTargetRevision(),
        payload.getUrl(),
        payload.getTitle(),
        payload.getContentHash(),
        payload.getStateQuality(),
        targets,
        payload.getDocumentReadyState(),
        payload.getNetworkQuietMillis(),
        payload.getNetworkEvidenceFresh(),
        payload.getSnapshotKind(),
        payload.getRequestedRootRef());
  }

  private void validateSnapshotManifest(
      String sessionId, String snapshotId, int totalChunks, long totalBytes, String payloadSha256) {
    requireText(sessionId, "session_id");
    validateSnapshotId(snapshotId);
    if (totalChunks <= 0
        || totalChunks > MAX_SNAPSHOT_CHUNKS
        || totalBytes <= 0
        || totalBytes > MAX_SNAPSHOT_BYTES
        || totalBytes > (long) totalChunks * MAX_SNAPSHOT_CHUNK_BYTES
        || totalBytes <= (long) (totalChunks - 1) * MAX_SNAPSHOT_CHUNK_BYTES
        || !payloadSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("snapshot manifest boundary metadata is invalid");
    }
  }

  private void validateSnapshotId(String snapshotId) {
    requireText(snapshotId, "snapshot_id");
    if (snapshotId.length() > 160 || snapshotId.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("snapshot_id is invalid");
    }
  }

  private String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void validateReadinessEvidence(String documentReadyState, long networkQuietMillis) {
    if (!documentReadyState.isBlank()
        && !java.util.Set.of("loading", "interactive", "complete").contains(documentReadyState)) {
      throw new IllegalArgumentException("unsupported document_ready_state");
    }
    if (networkQuietMillis < 0 || networkQuietMillis > 300_000) {
      throw new IllegalArgumentException("network_quiet_millis is outside the bounded range");
    }
  }

  private void validateDiffSnapshotMetadata(
      String snapshotKind, String requestedRootRef, String resyncRequestId) {
    if (snapshotKind.isBlank()) {
      if (!requestedRootRef.isBlank() || !resyncRequestId.isBlank()) {
        throw new IllegalArgumentException("legacy State Diff cannot carry requested_root_ref");
      }
      return;
    }
    if (!snapshotKind.equals("REGION_RESYNC")) {
      throw new IllegalArgumentException("unsupported State Diff snapshot_kind");
    }
    if (requestedRootRef.isBlank()
        || requestedRootRef.length() > 512
        || requestedRootRef.chars().anyMatch(Character::isISOControl)
        || !resyncRequestId.matches("^cmd_[a-zA-Z0-9]{16,}$")) {
      throw new IllegalArgumentException("REGION_RESYNC requested_root_ref is invalid");
    }
  }

  private String regionResyncRequestId(
      String snapshotKind, String resyncRequestId, String eventId) {
    if (!snapshotKind.equals("REGION_RESYNC") || !resyncRequestId.isBlank()) {
      return resyncRequestId;
    }
    if (eventId.startsWith("evt_")) {
      var legacyCommandId = eventId.substring("evt_".length());
      if (legacyCommandId.matches("^cmd_[a-zA-Z0-9]{16,}$")) {
        return legacyCommandId;
      }
    }
    return "";
  }

  private void validateCollectionCpuMillis(Long collectionCpuMillis) {
    if (collectionCpuMillis != null && (collectionCpuMillis < 0 || collectionCpuMillis > 300_000)) {
      throw new IllegalArgumentException("collection_cpu_millis is outside the bounded range");
    }
  }

  private void validateStateMetadata(
      String sessionId,
      long stateVersion,
      long targetRevision,
      String url,
      String title,
      String stateHash,
      String stateQuality) {
    requireText(sessionId, "session_id");
    if (stateVersion <= 0 || targetRevision <= 0) {
      throw new IllegalArgumentException("State versions must be positive");
    }
    if (url.isBlank() || url.length() > 8192 || title.length() > 1024) {
      throw new IllegalArgumentException("State document metadata is invalid");
    }
    requireText(stateHash, "content_hash");
    if (!java.util.Set.of("COMPLETE", "DEPTH_LIMITED", "RESYNCING", "DEGRADED", "INVALID")
        .contains(stateQuality)) {
      throw new IllegalArgumentException("unsupported state_quality");
    }
  }

  private NodeEvent.InteractiveTarget target(
      io.browsercloud.proto.node.v1.InteractiveTargetState target) {
    requireText(target.getTargetRef(), "target_ref");
    requireText(target.getRole(), "target_role");
    return new NodeEvent.InteractiveTarget(
        target.getTargetRef(),
        target.getRole(),
        target.hasName() ? target.getName() : null,
        target.hasBounds()
            ? new NodeEvent.Bounds(
                target.getBounds().getX(),
                target.getBounds().getY(),
                target.getBounds().getWidth(),
                target.getBounds().getHeight())
            : null,
        target.getEnabled(),
        target.getVisible(),
        target.getSensitive());
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(field + " must contain 1 to 128 characters");
    }
  }

  private boolean validExtensionPolicy(List<String> extensionIds) {
    return extensionIds == null
        || (extensionIds.size() <= 32
            && extensionIds.size() == extensionIds.stream().distinct().count()
            && extensionIds.stream()
                .allMatch(id -> id != null && id.matches("[A-Za-z0-9._-]{1,128}")));
  }
}
