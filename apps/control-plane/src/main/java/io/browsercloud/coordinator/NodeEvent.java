package io.browsercloud.coordinator;

import java.util.List;

/**
 * Node 事件。
 *
 * <p>Browser Node 发送到 Control Plane 的事件。
 */
public sealed interface NodeEvent
    permits NodeEvent.RuntimeStarted,
        NodeEvent.RuntimeStopped,
        NodeEvent.RuntimeResourcesAdjusted,
        NodeEvent.RuntimeCrashed,
        NodeEvent.StateUpdated,
        NodeEvent.StateSnapshotBegin,
        NodeEvent.StateSnapshotChunk,
        NodeEvent.StateSnapshotCommit,
        NodeEvent.StateDiff,
        NodeEvent.DiffTruncated,
        NodeEvent.AgentNavigationFailed,
        NodeEvent.AgentActionFailed,
        NodeEvent.HumanAssistFailed,
        NodeEvent.EvidenceCaptured,
        NodeEvent.HumanTakeoverReady,
        NodeEvent.HumanTakeoverEnded {

  /** Runtime 启动事件。 */
  record RuntimeStarted(
      String sessionId,
      String nodeId,
      String runtimeBuildId,
      long pid,
      long browserGeneration,
      String cdpEndpoint,
      String proxyBindingId,
      String exitIp,
      String exitCountry,
      String exitAsn)
      implements NodeEvent {}

  /** Runtime 停止事件。 */
  record RuntimeStopped(
      String sessionId,
      String reason,
      int exitCode,
      String profileId,
      String checkpointId,
      long checkpointEpoch,
      long profileWriteEpoch,
      long coreSizeBytes,
      long checkpointFileCount,
      String restoreStatus)
      implements NodeEvent {}

  record RuntimeResourcesAdjusted(
      String sessionId,
      String nodeId,
      String oldResourceClass,
      int oldCpuMillis,
      int oldMemoryRequestMib,
      int oldMemoryLimitMib,
      int oldPidLimit,
      int oldTabBudget,
      String newResourceClass,
      int newCpuMillis,
      int newMemoryRequestMib,
      int newMemoryLimitMib,
      int newPidLimit,
      int newTabBudget,
      Integer oldStateCollectorBudgetPercent,
      Integer oldRemoteDesktopBitrateKbps,
      Integer newStateCollectorBudgetPercent,
      Integer newRemoteDesktopBitrateKbps,
      Integer oldExtensionCpuWeight,
      Integer newExtensionCpuWeight,
      Integer oldMediaEncoderSlots,
      Integer newMediaEncoderSlots,
      Boolean oldFreezeBackgroundTabs,
      Boolean newFreezeBackgroundTabs,
      Boolean oldBlockNewTabs,
      Boolean newBlockNewTabs,
      List<String> oldPausedExtensionIds,
      List<String> newPausedExtensionIds,
      Integer oldSuccessTraceSamplePercent,
      Integer newSuccessTraceSamplePercent,
      Integer oldObserverFrameRateFps,
      Integer newObserverFrameRateFps,
      Boolean oldVideoRecordingEnabled,
      Boolean newVideoRecordingEnabled,
      Integer oldSuccessScreenshotSamplePercent,
      Integer newSuccessScreenshotSamplePercent,
      String reason,
      String operationId)
      implements NodeEvent {
    public RuntimeResourcesAdjusted {
      oldPausedExtensionIds =
          oldPausedExtensionIds == null ? null : List.copyOf(oldPausedExtensionIds);
      newPausedExtensionIds =
          newPausedExtensionIds == null ? null : List.copyOf(newPausedExtensionIds);
    }

    public RuntimeResourcesAdjusted(
        String sessionId,
        String nodeId,
        String oldResourceClass,
        int oldCpuMillis,
        int oldMemoryRequestMib,
        int oldMemoryLimitMib,
        int oldPidLimit,
        int oldTabBudget,
        String newResourceClass,
        int newCpuMillis,
        int newMemoryRequestMib,
        int newMemoryLimitMib,
        int newPidLimit,
        int newTabBudget,
        Integer oldStateCollectorBudgetPercent,
        Integer oldRemoteDesktopBitrateKbps,
        Integer newStateCollectorBudgetPercent,
        Integer newRemoteDesktopBitrateKbps,
        Integer oldExtensionCpuWeight,
        Integer newExtensionCpuWeight,
        Integer oldMediaEncoderSlots,
        Integer newMediaEncoderSlots,
        Boolean oldFreezeBackgroundTabs,
        Boolean newFreezeBackgroundTabs,
        Boolean oldBlockNewTabs,
        Boolean newBlockNewTabs,
        List<String> oldPausedExtensionIds,
        List<String> newPausedExtensionIds,
        Integer oldSuccessTraceSamplePercent,
        Integer newSuccessTraceSamplePercent,
        Integer oldObserverFrameRateFps,
        Integer newObserverFrameRateFps,
        String reason,
        String operationId) {
      this(
          sessionId,
          nodeId,
          oldResourceClass,
          oldCpuMillis,
          oldMemoryRequestMib,
          oldMemoryLimitMib,
          oldPidLimit,
          oldTabBudget,
          newResourceClass,
          newCpuMillis,
          newMemoryRequestMib,
          newMemoryLimitMib,
          newPidLimit,
          newTabBudget,
          oldStateCollectorBudgetPercent,
          oldRemoteDesktopBitrateKbps,
          newStateCollectorBudgetPercent,
          newRemoteDesktopBitrateKbps,
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
          null,
          null,
          null,
          null,
          reason,
          operationId);
    }
  }

  /** Runtime 崩溃事件。 */
  record RuntimeCrashed(String sessionId, String crashType, String reason) implements NodeEvent {}

  /** 状态更新事件。 */
  record StateUpdated(
      String sessionId,
      long stateVersion,
      long targetRevision,
      String url,
      String title,
      String stateHash,
      String stateQuality,
      List<InteractiveTarget> targets,
      String documentReadyState,
      long networkQuietMillis,
      boolean networkEvidenceFresh,
      String snapshotKind,
      String requestedRootRef)
      implements NodeEvent {
    public StateUpdated {
      targets = List.copyOf(targets);
    }

    public StateUpdated(
        String sessionId,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        String stateHash,
        String stateQuality,
        List<InteractiveTarget> targets) {
      this(
          sessionId,
          stateVersion,
          targetRevision,
          url,
          title,
          stateHash,
          stateQuality,
          targets,
          "",
          0,
          false,
          "",
          "");
    }

    public StateUpdated(
        String sessionId,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        String stateHash,
        String stateQuality,
        List<InteractiveTarget> targets,
        String snapshotKind,
        String requestedRootRef) {
      this(
          sessionId,
          stateVersion,
          targetRevision,
          url,
          title,
          stateHash,
          stateQuality,
          targets,
          "",
          0,
          false,
          snapshotKind,
          requestedRootRef);
    }
  }

  record StateSnapshotBegin(
      String sessionId,
      String snapshotId,
      long stateVersion,
      long targetRevision,
      int totalChunks,
      long totalBytes,
      String payloadSha256,
      String snapshotKind,
      Long collectionCpuMillis)
      implements NodeEvent {
    public StateSnapshotBegin(
        String sessionId,
        String snapshotId,
        long stateVersion,
        long targetRevision,
        int totalChunks,
        long totalBytes,
        String payloadSha256,
        String snapshotKind) {
      this(
          sessionId,
          snapshotId,
          stateVersion,
          targetRevision,
          totalChunks,
          totalBytes,
          payloadSha256,
          snapshotKind,
          null);
    }
  }

  record StateSnapshotChunk(
      String sessionId,
      String snapshotId,
      int chunkIndex,
      int totalChunks,
      byte[] data,
      String chunkSha256)
      implements NodeEvent {
    public StateSnapshotChunk {
      data = data.clone();
    }

    @Override
    public byte[] data() {
      return data.clone();
    }
  }

  record StateSnapshotCommit(
      String sessionId, String snapshotId, int totalChunks, long totalBytes, String payloadSha256)
      implements NodeEvent {}

  record StateDiff(
      String sessionId,
      long baseStateVersion,
      long stateVersion,
      long targetRevision,
      String url,
      String title,
      String stateHash,
      String stateQuality,
      String documentReadyState,
      long networkQuietMillis,
      boolean networkEvidenceFresh,
      List<InteractiveTarget> upsertedTargets,
      List<String> removedTargetRefs,
      String snapshotKind,
      String requestedRootRef,
      String resyncRequestId,
      long snapshotBytes,
      Long collectionCpuMillis)
      implements NodeEvent {
    public StateDiff {
      upsertedTargets = List.copyOf(upsertedTargets);
      removedTargetRefs = List.copyOf(removedTargetRefs);
    }

    public StateDiff(
        String sessionId,
        long baseStateVersion,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        String stateHash,
        String stateQuality,
        String documentReadyState,
        long networkQuietMillis,
        boolean networkEvidenceFresh,
        List<InteractiveTarget> upsertedTargets,
        List<String> removedTargetRefs,
        String snapshotKind,
        String requestedRootRef) {
      this(
          sessionId,
          baseStateVersion,
          stateVersion,
          targetRevision,
          url,
          title,
          stateHash,
          stateQuality,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          upsertedTargets,
          removedTargetRefs,
          snapshotKind,
          requestedRootRef,
          "",
          0,
          null);
    }

    public StateDiff(
        String sessionId,
        long baseStateVersion,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        String stateHash,
        String stateQuality,
        String documentReadyState,
        long networkQuietMillis,
        boolean networkEvidenceFresh,
        List<InteractiveTarget> upsertedTargets,
        List<String> removedTargetRefs) {
      this(
          sessionId,
          baseStateVersion,
          stateVersion,
          targetRevision,
          url,
          title,
          stateHash,
          stateQuality,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          upsertedTargets,
          removedTargetRefs,
          "",
          "",
          "",
          0,
          null);
    }

    public StateDiff(
        String sessionId,
        long baseStateVersion,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        String stateHash,
        String stateQuality,
        List<InteractiveTarget> upsertedTargets,
        List<String> removedTargetRefs) {
      this(
          sessionId,
          baseStateVersion,
          stateVersion,
          targetRevision,
          url,
          title,
          stateHash,
          stateQuality,
          "",
          0,
          false,
          upsertedTargets,
          removedTargetRefs,
          "",
          "",
          "",
          0,
          null);
    }
  }

  record DiffTruncated(
      String sessionId,
      String reason,
      long lastGoodStateVersion,
      long currentStateVersion,
      String affectedRoot,
      long estimatedTargets)
      implements NodeEvent {}

  record AgentNavigationFailed(String sessionId, String taskId, String stepId, String errorCode)
      implements NodeEvent {}

  record AgentActionFailed(
      String sessionId, String taskId, String stepId, String toolId, String errorCode)
      implements NodeEvent {}

  record HumanAssistFailed(
      String sessionId, String challengeEventId, String intentId, String errorCode)
      implements NodeEvent {}

  record EvidenceCaptured(
      String sessionId,
      String evidenceId,
      String evidenceKind,
      String taskId,
      String stepId,
      String commandId,
      String contentSha256,
      long contentBytes,
      String objectKey,
      long capturedAtMs,
      boolean mandatory,
      String result,
      String errorCode,
      String redactionState,
      int redactedRegionCount)
      implements NodeEvent {}

  record InteractiveTarget(
      String targetRef,
      String role,
      String name,
      Bounds bounds,
      boolean enabled,
      boolean visible,
      boolean sensitive) {}

  record Bounds(double x, double y, double width, double height) {}

  record HumanTakeoverReady(String sessionId, String userId, StateUpdated state)
      implements NodeEvent {}

  record HumanTakeoverEnded(String sessionId, String userId, String reason, StateUpdated state)
      implements NodeEvent {}
}
