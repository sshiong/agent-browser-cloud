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
        NodeEvent.ProfileWarmTierSynced,
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
        NodeEvent.ChallengeAutomationFailed,
        NodeEvent.RemoteDesktopParticipantChanged,
        NodeEvent.EvidenceCaptured,
        NodeEvent.RecordingFinalized,
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

  record RemoteDesktopParticipantChanged(
      String sessionId,
      String connectionId,
      String actorId,
      String accessMode,
      boolean viewOnly,
      String state,
      String reason,
      long observedAtMs,
      String revokedBy,
      long forwardedBytes,
      long quotaWaitMillis,
      long throttledBatches)
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

  record ProfileWarmTierSynced(
      String sessionId,
      String nodeId,
      String profileId,
      long profileWriteEpoch,
      long journalSequence,
      String transactionBarrier,
      long changedFileCount,
      long deletedFileCount,
      long reusedChunkCount,
      long uploadedBytes,
      long deferredGroupCount,
      String manifestSha256,
      long committedAtMs)
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
      List<BrowserTab> tabs,
      String activeTabId,
      String stateHash,
      String stateQuality,
      List<InteractiveTarget> targets,
      String documentReadyState,
      long networkQuietMillis,
      boolean networkEvidenceFresh,
      String snapshotKind,
      String requestedRootRef,
      List<AgentActionOutcome> actionOutcomes,
      List<NativeDialog> nativeDialogs,
      boolean nativeDialogEvidenceFresh)
      implements NodeEvent {
    public StateUpdated {
      tabs = tabs == null ? List.of() : List.copyOf(tabs);
      activeTabId = activeTabId == null ? "" : activeTabId;
      targets = List.copyOf(targets);
      actionOutcomes = actionOutcomes == null ? List.of() : List.copyOf(actionOutcomes);
      nativeDialogs = nativeDialogs == null ? List.of() : List.copyOf(nativeDialogs);
    }

    public StateUpdated(
        String sessionId,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        List<BrowserTab> tabs,
        String activeTabId,
        String stateHash,
        String stateQuality,
        List<InteractiveTarget> targets,
        String documentReadyState,
        long networkQuietMillis,
        boolean networkEvidenceFresh,
        String snapshotKind,
        String requestedRootRef,
        List<AgentActionOutcome> actionOutcomes) {
      this(
          sessionId,
          stateVersion,
          targetRevision,
          url,
          title,
          tabs,
          activeTabId,
          stateHash,
          stateQuality,
          targets,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          snapshotKind,
          requestedRootRef,
          actionOutcomes,
          List.of(),
          false);
    }

    public StateUpdated(
        String sessionId,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        List<BrowserTab> tabs,
        String activeTabId,
        String stateHash,
        String stateQuality,
        List<InteractiveTarget> targets,
        String documentReadyState,
        long networkQuietMillis,
        boolean networkEvidenceFresh,
        String snapshotKind,
        String requestedRootRef) {
      this(
          sessionId,
          stateVersion,
          targetRevision,
          url,
          title,
          tabs,
          activeTabId,
          stateHash,
          stateQuality,
          targets,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          snapshotKind,
          requestedRootRef,
          List.of());
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
        String documentReadyState,
        long networkQuietMillis,
        boolean networkEvidenceFresh,
        String snapshotKind,
        String requestedRootRef,
        List<AgentActionOutcome> actionOutcomes) {
      this(
          sessionId,
          stateVersion,
          targetRevision,
          url,
          title,
          List.of(),
          "",
          stateHash,
          stateQuality,
          targets,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          snapshotKind,
          requestedRootRef,
          actionOutcomes);
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
        String documentReadyState,
        long networkQuietMillis,
        boolean networkEvidenceFresh,
        String snapshotKind,
        String requestedRootRef) {
      this(
          sessionId,
          stateVersion,
          targetRevision,
          url,
          title,
          List.of(),
          "",
          stateHash,
          stateQuality,
          targets,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          snapshotKind,
          requestedRootRef,
          List.of());
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
          List.of(),
          "",
          stateHash,
          stateQuality,
          targets,
          "",
          0,
          false,
          "",
          "",
          List.of());
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
          List.of(),
          "",
          stateHash,
          stateQuality,
          targets,
          "",
          0,
          false,
          snapshotKind,
          requestedRootRef,
          List.of());
    }
  }

  record BrowserTab(String tabId, String url, String title, boolean active) {}

  record NativeDialog(
      String dialogId,
      String tabId,
      String dialogType,
      String message,
      String defaultPrompt,
      boolean hasBrowserHandler) {}

  record AgentActionOutcome(
      String actionId, String status, String errorCode, long stateVersion, long targetRevision) {}

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
      List<BrowserTab> tabs,
      String activeTabId,
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
      Long collectionCpuMillis,
      List<NativeDialog> nativeDialogs,
      boolean nativeDialogEvidenceFresh)
      implements NodeEvent {
    public StateDiff {
      tabs = tabs == null ? List.of() : List.copyOf(tabs);
      activeTabId = activeTabId == null ? "" : activeTabId;
      upsertedTargets = List.copyOf(upsertedTargets);
      removedTargetRefs = List.copyOf(removedTargetRefs);
      nativeDialogs = nativeDialogs == null ? List.of() : List.copyOf(nativeDialogs);
    }

    public StateDiff(
        String sessionId,
        long baseStateVersion,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        List<BrowserTab> tabs,
        String activeTabId,
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
        Long collectionCpuMillis) {
      this(
          sessionId,
          baseStateVersion,
          stateVersion,
          targetRevision,
          url,
          title,
          tabs,
          activeTabId,
          stateHash,
          stateQuality,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          upsertedTargets,
          removedTargetRefs,
          snapshotKind,
          requestedRootRef,
          resyncRequestId,
          snapshotBytes,
          collectionCpuMillis,
          List.of(),
          false);
    }

    public StateDiff(
        String sessionId,
        long baseStateVersion,
        long stateVersion,
        long targetRevision,
        String url,
        String title,
        List<BrowserTab> tabs,
        String activeTabId,
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
          tabs,
          activeTabId,
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
        List<String> removedTargetRefs,
        String snapshotKind,
        String requestedRootRef,
        String resyncRequestId,
        long snapshotBytes,
        Long collectionCpuMillis) {
      this(
          sessionId,
          baseStateVersion,
          stateVersion,
          targetRevision,
          url,
          title,
          List.of(),
          "",
          stateHash,
          stateQuality,
          documentReadyState,
          networkQuietMillis,
          networkEvidenceFresh,
          upsertedTargets,
          removedTargetRefs,
          snapshotKind,
          requestedRootRef,
          resyncRequestId,
          snapshotBytes,
          collectionCpuMillis);
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
          List.of(),
          "",
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
          List.of(),
          "",
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
          List.of(),
          "",
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

  record ChallengeAutomationFailed(
      String sessionId,
      String runId,
      String jobId,
      String challengeEventId,
      int attemptNumber,
      String errorCode)
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

  record RecordingFinalized(
      String sessionId,
      String recordingId,
      String nodeId,
      long segmentCount,
      long frameCount,
      long droppedFrames,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      String manifestObjectKey,
      String manifestSha256,
      long manifestBytes,
      long startedAtMs,
      long endedAtMs)
      implements NodeEvent {}

  record InteractiveTarget(
      String targetRef,
      String role,
      String name,
      Bounds bounds,
      boolean enabled,
      boolean visible,
      boolean sensitive,
      String elementId,
      String value,
      String controlType,
      boolean focused,
      Boolean checked,
      Boolean selected,
      boolean interactive,
      String frameId,
      boolean inViewport,
      boolean occluded,
      String visibilityReason) {
    /** Additive N/N-1 constructor for Browser State persisted by older Nodes. */
    public InteractiveTarget(
        String targetRef,
        String role,
        String name,
        Bounds bounds,
        boolean enabled,
        boolean visible,
        boolean sensitive) {
      this(
          targetRef,
          role,
          name,
          bounds,
          enabled,
          visible,
          sensitive,
          targetRef,
          null,
          null,
          false,
          null,
          null,
          true,
          "main",
          visible,
          false,
          visible ? null : "LEGACY_VISIBILITY_UNKNOWN");
    }
  }

  record Bounds(double x, double y, double width, double height) {}

  record HumanTakeoverReady(String sessionId, String userId, StateUpdated state)
      implements NodeEvent {}

  record HumanTakeoverEnded(String sessionId, String userId, String reason, StateUpdated state)
      implements NodeEvent {}
}
