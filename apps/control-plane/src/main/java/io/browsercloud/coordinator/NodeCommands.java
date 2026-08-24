package io.browsercloud.coordinator;

import io.browsercloud.domain.agent.AgentModels.PlanStep;
import io.browsercloud.domain.capacity.BrowserResourceBudget;
import io.browsercloud.domain.capacity.RuntimeResourceLimits;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.proto.node.v1.AdjustRuntimeResourcesCommand;
import io.browsercloud.proto.node.v1.AgentActionCommand;
import io.browsercloud.proto.node.v1.AgentActionPrimitive;
import io.browsercloud.proto.node.v1.AgentFileUploadCommand;
import io.browsercloud.proto.node.v1.AgentNavigateCommand;
import io.browsercloud.proto.node.v1.BeginHumanTakeoverCommand;
import io.browsercloud.proto.node.v1.BusinessRecoveryActionCommand;
import io.browsercloud.proto.node.v1.CaptureAgentScreenshotCommand;
import io.browsercloud.proto.node.v1.CaptureObserverScreenshotCommand;
import io.browsercloud.proto.node.v1.ChallengeAutomationActionCommand;
import io.browsercloud.proto.node.v1.ChallengeVisualAction;
import io.browsercloud.proto.node.v1.EndHumanTakeoverCommand;
import io.browsercloud.proto.node.v1.ExtensionBackgroundPolicy;
import io.browsercloud.proto.node.v1.HumanAssistClickCommand;
import io.browsercloud.proto.node.v1.ReleaseAllInputCommand;
import io.browsercloud.proto.node.v1.RequestStateResyncCommand;
import io.browsercloud.proto.node.v1.RevokeRemoteDesktopConnectionCommand;
import io.browsercloud.proto.node.v1.StartRuntimeCommand;
import io.browsercloud.proto.node.v1.StopRuntimeCommand;
import java.util.UUID;

/**
 * Node 命令构建器。
 *
 * <p>负责构建发送给 Browser Node 的命令。
 */
public final class NodeCommands {

  private NodeCommands() {}

  /** 构建 StartRuntime 命令。 */
  public static NodeCommand startRuntime(
      SessionContext session,
      ExclusiveOperation operation,
      String requestedRuntimeBuildId,
      RuntimeResourceLimits requestedLimits) {
    return startRuntime(session, operation, requestedRuntimeBuildId, requestedLimits, null, null);
  }

  public static NodeCommand startRuntime(
      SessionContext session,
      ExclusiveOperation operation,
      String requestedRuntimeBuildId,
      RuntimeResourceLimits requestedLimits,
      String profileCheckpointId) {
    return startRuntime(
        session, operation, requestedRuntimeBuildId, requestedLimits, profileCheckpointId, null);
  }

  public static NodeCommand startRuntime(
      SessionContext session,
      ExclusiveOperation operation,
      String requestedRuntimeBuildId,
      RuntimeResourceLimits requestedLimits,
      String profileCheckpointId,
      ProxyRuntimeBinding proxyBinding) {
    return startRuntime(
        session,
        operation,
        requestedRuntimeBuildId,
        requestedLimits,
        profileCheckpointId,
        proxyBinding,
        BrowserTransactionPolicy.empty());
  }

  public static NodeCommand startRuntime(
      SessionContext session,
      ExclusiveOperation operation,
      String requestedRuntimeBuildId,
      RuntimeResourceLimits requestedLimits,
      String profileCheckpointId,
      ProxyRuntimeBinding proxyBinding,
      BrowserTransactionPolicy browserTransactionPolicy) {
    return startRuntime(
        session,
        operation,
        requestedRuntimeBuildId,
        requestedLimits,
        profileCheckpointId,
        proxyBinding,
        browserTransactionPolicy,
        BrowserIdentitySpec.empty());
  }

  public static NodeCommand startRuntime(
      SessionContext session,
      ExclusiveOperation operation,
      String requestedRuntimeBuildId,
      RuntimeResourceLimits requestedLimits,
      String profileCheckpointId,
      ProxyRuntimeBinding proxyBinding,
      BrowserTransactionPolicy browserTransactionPolicy,
      BrowserIdentitySpec identitySpec) {
    var limits = requestedLimits == null ? defaultLimits(session) : requestedLimits;
    if (limits.resourceClass() != session.resourceClass()) {
      throw new IllegalArgumentException("Runtime limits do not match committed Resource Class");
    }
    var payload =
        StartRuntimeCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setRuntimeBuildId(requestedRuntimeBuildId)
            .setProfileId(session.profileId())
            .setDisplay("")
            .setCdpPort(0)
            .setProxyBindingId(session.proxyBindingId() == null ? "" : session.proxyBindingId())
            .setResourceClass(limits.resourceClass().name())
            .setCpuMillis(limits.cpuMillis())
            .setMemoryRequestMib(limits.memoryRequestMib())
            .setMemoryLimitMib(limits.memoryLimitMib())
            .setPidLimit(limits.pidLimit())
            .setTabBudget(limits.tabBudget())
            .setStateCollectorBudgetPercent(limits.stateCollectorBudgetPercent())
            .setRemoteDesktopBitrateKbps(limits.remoteDesktopBitrateKbps())
            .addAllExtensionIds(limits.extensionIds())
            .setExtensionCpuWeight(limits.extensionCpuWeight())
            .setMediaEncoderSlots(limits.mediaEncoderSlots())
            .setFreezeBackgroundTabs(limits.freezeBackgroundTabs())
            .setBlockNewTabs(limits.blockNewTabs())
            .setExtensionBackgroundPolicy(
                ExtensionBackgroundPolicy.newBuilder()
                    .addAllPausedExtensionIds(limits.pausedExtensionIds()))
            .setSuccessTraceSamplePercent(limits.successTraceSamplePercent())
            .setObserverFrameRateFps(limits.observerFrameRateFps())
            .setVideoRecordingEnabled(limits.videoRecordingEnabled())
            .setSuccessScreenshotSamplePercent(limits.successScreenshotSamplePercent())
            .setMinimumBrowserGeneration(session.browserGeneration())
            .addAllBrowserTransactionExpectedOrigins(browserTransactionPolicy.expectedOrigins())
            .addAllPaymentSecurityRoutePrefixes(
                browserTransactionPolicy.paymentSecurityRoutePrefixes())
            .addAllCriticalTransactionRoutePrefixes(
                browserTransactionPolicy.criticalTransactionRoutePrefixes())
            .setBrowserTransactionPolicyHash(browserTransactionPolicy.policyHash())
            .setBrowserTransactionPolicyVersion(browserTransactionPolicy.version())
            .setDesktopRequired(limits.desktop())
            .setGpuRequired(limits.gpu())
            .setNativeOsRequired(limits.nativeOs())
            .setIsolationRequired(limits.isolated())
            .setProfileCheckpointId(profileCheckpointId == null ? "" : profileCheckpointId)
            .setIdentityUserAgent(value(identitySpec.userAgent()))
            .setIdentityTimezone(value(identitySpec.timezone()))
            .setIdentityLocale(value(identitySpec.locale()))
            .addAllIdentityLanguages(identitySpec.languages())
            .setIdentityWebrtcPolicy(value(identitySpec.webRtcPolicy()))
            .setIdentityDnsPolicy(value(identitySpec.dnsPolicy()))
            .setIdentityViewportWidth(number(identitySpec.viewportWidth()))
            .setIdentityViewportHeight(number(identitySpec.viewportHeight()))
            .setIdentityScreenWidth(number(identitySpec.screenWidth()))
            .setIdentityScreenHeight(number(identitySpec.screenHeight()))
            .setIdentityDeviceScaleFactor(
                identitySpec.deviceScaleFactor() == null
                    ? 0
                    : identitySpec.deviceScaleFactor().doubleValue())
            .setIdentityFingerprintProfile(value(identitySpec.fingerprintProfile()))
            .setIdentityOperatingSystemProfile(value(identitySpec.operatingSystemProfile()))
            .setIdentitySpecVersion(identitySpec.version())
            .setIdentitySpecHash(value(identitySpec.specHash()));
    if (proxyBinding != null) {
      if (!proxyBinding.bindingId().equals(session.proxyBindingId())) {
        throw new IllegalArgumentException(
            "Runtime proxy descriptor does not match committed Session binding");
      }
      payload
          .setProxyProviderId(proxyBinding.providerId())
          .setProxyExpectedExitIp(proxyBinding.expectedExitIp())
          .setProxyCredentialRef(proxyBinding.credentialRef());
    }
    return new NodeCommand(
        newId("cmd_"),
        "StartRuntime",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        operation.operationEpoch(),
        operation.operationId(),
        payload.build().toByteArray());
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private static int number(Integer value) {
    return value == null ? 0 : value;
  }

  private static RuntimeResourceLimits defaultLimits(SessionContext session) {
    var budget = BrowserResourceBudget.of(session.resourceClass());
    return new RuntimeResourceLimits(
        budget.resourceClass(),
        budget.cpuMillis(),
        budget.memoryRequestMib(),
        budget.memoryLimitMib(),
        budget.pidLimit(),
        budget.tabBudget(),
        50,
        budget.desktopAllowed() ? 8_000 : 0,
        java.util.List.of(),
        100,
        0,
        false,
        false,
        java.util.List.of(),
        100,
        100,
        budget.desktopAllowed() ? 30 : 0,
        false,
        budget.desktopAllowed(),
        budget.gpuRequired(),
        budget.nativeOsRequired(),
        false);
  }

  public static NodeCommand adjustRuntimeResources(
      SessionContext session,
      ExclusiveOperation operation,
      RuntimeResourceLimits limits,
      String reason) {
    return adjustRuntimeResources(session, operation, limits, reason, false, false);
  }

  public static NodeCommand adjustRuntimeResources(
      SessionContext session,
      ExclusiveOperation operation,
      RuntimeResourceLimits limits,
      String reason,
      boolean freezeBackgroundTabs,
      boolean blockNewTabs) {
    var payload =
        AdjustRuntimeResourcesCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setResourceClass(limits.resourceClass().name())
            .setCpuMillis(limits.cpuMillis())
            .setMemoryRequestMib(limits.memoryRequestMib())
            .setMemoryLimitMib(limits.memoryLimitMib())
            .setPidLimit(limits.pidLimit())
            .setTabBudget(limits.tabBudget())
            .setStateCollectorBudgetPercent(limits.stateCollectorBudgetPercent())
            .setRemoteDesktopBitrateKbps(limits.remoteDesktopBitrateKbps())
            .setExtensionCpuWeight(limits.extensionCpuWeight())
            .setMediaEncoderSlots(limits.mediaEncoderSlots())
            .setFreezeBackgroundTabs(freezeBackgroundTabs)
            .setBlockNewTabs(blockNewTabs)
            .addAllExtensionIds(limits.extensionIds())
            .setExtensionBackgroundPolicy(
                ExtensionBackgroundPolicy.newBuilder()
                    .addAllPausedExtensionIds(limits.pausedExtensionIds()))
            .setSuccessTraceSamplePercent(limits.successTraceSamplePercent())
            .setObserverFrameRateFps(limits.observerFrameRateFps())
            .setVideoRecordingEnabled(limits.videoRecordingEnabled())
            .setSuccessScreenshotSamplePercent(limits.successScreenshotSamplePercent())
            .setDesktopRequired(limits.desktop())
            .setGpuRequired(limits.gpu())
            .setNativeOsRequired(limits.nativeOs())
            .setIsolationRequired(limits.isolated())
            .setReason(reason)
            .build()
            .toByteArray();
    return command(session, operation, "AdjustRuntimeResources", payload);
  }

  /** 构建 StopRuntime 命令。 */
  public static NodeCommand stopRuntime(
      SessionContext session, ExclusiveOperation operation, String reason) {
    var payload =
        StopRuntimeCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setReason(reason)
            .build()
            .toByteArray();
    return new NodeCommand(
        newId("cmd_"),
        "StopRuntime",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        operation.operationEpoch(),
        operation.operationId(),
        payload);
  }

  public static NodeCommand beginHumanTakeover(
      SessionContext session, ExclusiveOperation operation) {
    var payload =
        BeginHumanTakeoverCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setUserId(operation.actorId())
            .build()
            .toByteArray();
    return command(session, operation, "BeginHumanTakeover", payload);
  }

  public static NodeCommand endHumanTakeover(SessionContext session, ExclusiveOperation operation) {
    var payload =
        EndHumanTakeoverCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setUserId(operation.actorId())
            .build()
            .toByteArray();
    return command(session, operation, "EndHumanTakeover", payload);
  }

  public static NodeCommand humanAssistClick(
      SessionContext session,
      ExclusiveOperation operation,
      String challengeEventId,
      String intentId,
      String targetRef,
      long targetRevision,
      long stateVersion,
      String stateHash,
      NodeEvent.Bounds bounds,
      String visualAnchorHash) {
    var payload =
        HumanAssistClickCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setChallengeEventId(challengeEventId)
            .setIntentId(intentId)
            .setTargetRef(targetRef)
            .setTargetRevision(targetRevision)
            .setBaseStateVersion(stateVersion)
            .setBaseContentHash(stateHash)
            .setAllowedActionCount(1)
            .setExpectedX(bounds.x())
            .setExpectedY(bounds.y())
            .setExpectedWidth(bounds.width())
            .setExpectedHeight(bounds.height())
            .setVisualAnchorHash(visualAnchorHash)
            .build()
            .toByteArray();
    return command(session, operation, "HumanAssistClick", payload);
  }

  /**
   * Coordinator 换主后释放旧世代可能遗留的全部输入状态。
   *
   * <p>该命令不绑定新的 Exclusive Operation，Node 仅按 coordinator term fencing 并以 message id 去重。reason
   * 只用于诊断，不能承载敏感数据。
   */
  public static NodeCommand releaseAllInput(
      SessionContext session, ExclusiveOperation staleOperation, String reason) {
    var payload =
        ReleaseAllInputCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setReason(reason)
            .build()
            .toByteArray();
    return new NodeCommand(
        newId("cmd_"),
        "ReleaseAllInput",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        0,
        "failover-fence:" + staleOperation.operationId(),
        payload);
  }

  /** Revokes one exact VNC participant without acquiring an exclusive Session operation. */
  public static NodeCommand revokeRemoteDesktopConnection(
      SessionContext session,
      String connectionId,
      String reason,
      String revokedBy,
      String idempotencyKey) {
    var payload =
        RevokeRemoteDesktopConnectionCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setConnectionId(connectionId)
            .setReason(reason)
            .setRevokedBy(revokedBy)
            .build()
            .toByteArray();
    return new NodeCommand(
        newId("cmd_"),
        "RevokeRemoteDesktopConnection",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        0,
        idempotencyKey,
        payload);
  }

  public static NodeCommand requestStateResync(
      SessionContext session, String mode, String rootRef, String reason, String idempotencyKey) {
    var payload =
        RequestStateResyncCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setMode(mode)
            .setRootRef(rootRef)
            .setReason(reason)
            .build()
            .toByteArray();
    return new NodeCommand(
        newId("cmd_"),
        "RequestStateResync",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        0,
        idempotencyKey,
        payload);
  }

  public static NodeCommand agentNavigate(
      SessionContext session,
      ExclusiveOperation operation,
      String taskId,
      String stepId,
      String url,
      long baseStateVersion) {
    var payload =
        AgentNavigateCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setTaskId(taskId)
            .setStepId(stepId)
            .setUrl(url)
            .setBaseStateVersion(baseStateVersion)
            .build()
            .toByteArray();
    return command(session, operation, "AgentNavigate", payload);
  }

  public static NodeCommand businessRecoveryAction(
      SessionContext session,
      String messageId,
      String actionId,
      String action,
      String targetUrl,
      String targetExtensionId,
      long baseStateVersion) {
    var payload =
        BusinessRecoveryActionCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setActionId(actionId)
            .setAction(action)
            .setTargetUrl(targetUrl == null ? "" : targetUrl)
            .setExtensionId(targetExtensionId == null ? "" : targetExtensionId)
            .setBaseStateVersion(baseStateVersion)
            .build()
            .toByteArray();
    return new NodeCommand(
        messageId,
        "BusinessRecoveryAction",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        0,
        "business-recovery:" + actionId,
        payload);
  }

  public static NodeCommand captureObserverScreenshot(
      SessionContext session, String captureId, String commandId) {
    var payload =
        CaptureObserverScreenshotCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setCaptureId(captureId)
            .build()
            .toByteArray();
    return new NodeCommand(
        commandId,
        "CaptureObserverScreenshot",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        0,
        "observer-evidence:" + captureId,
        payload);
  }

  public static NodeCommand captureAgentScreenshot(
      SessionContext session,
      String screenshotId,
      String evidenceId,
      String commandId,
      String mode,
      long stateVersion,
      long targetRevision,
      String stateHash,
      String activeTabId,
      String elementId,
      Double regionX,
      Double regionY,
      Double regionWidth,
      Double regionHeight,
      long capturedAtMs) {
    var payload =
        CaptureAgentScreenshotCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setScreenshotId(screenshotId)
            .setCaptureMode(mode)
            .setBaseStateVersion(stateVersion)
            .setTargetRevision(targetRevision)
            .setBaseContentHash(stateHash)
            .setActiveTabId(activeTabId)
            .setElementId(elementId == null ? "" : elementId)
            .setRegionX(regionX == null ? 0 : regionX)
            .setRegionY(regionY == null ? 0 : regionY)
            .setRegionWidth(regionWidth == null ? 0 : regionWidth)
            .setRegionHeight(regionHeight == null ? 0 : regionHeight)
            .setEvidenceId(evidenceId)
            .setCapturedAtMs(capturedAtMs)
            .build()
            .toByteArray();
    return new NodeCommand(
        commandId,
        "CaptureAgentScreenshot",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        0,
        "agent-screenshot:" + screenshotId,
        payload);
  }

  public static NodeCommand challengeAutomationAction(
      SessionContext session,
      ExclusiveOperation operation,
      String runId,
      String jobId,
      String challengeEventId,
      int attemptNumber,
      long baseStateVersion,
      String baseContentHash,
      java.util.List<io.browsercloud.api.ChallengeAutomationModels.ChallengeVisualAction> actions,
      int motionMinimumSteps,
      int motionMaximumSteps,
      int motionMinimumDelayMs,
      int motionMaximumDelayMs,
      java.math.BigDecimal targetOffsetRatio) {
    var payload =
        ChallengeAutomationActionCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setRunId(runId)
            .setJobId(jobId)
            .setChallengeEventId(challengeEventId)
            .setAttemptNumber(attemptNumber)
            .setBaseStateVersion(baseStateVersion)
            .setBaseContentHash(baseContentHash)
            .setMotionMinSteps(motionMinimumSteps)
            .setMotionMaxSteps(motionMaximumSteps)
            .setMotionMinDelayMs(motionMinimumDelayMs)
            .setMotionMaxDelayMs(motionMaximumDelayMs)
            .setTargetOffsetRatio(targetOffsetRatio.doubleValue());
    actions.forEach(
        action ->
            payload.addActions(
                ChallengeVisualAction.newBuilder()
                    .setActionType(action.actionType().name())
                    .setX(action.x().doubleValue())
                    .setY(action.y().doubleValue())
                    .setEndX(action.endX() == null ? 0 : action.endX().doubleValue())
                    .setEndY(action.endY() == null ? 0 : action.endY().doubleValue())
                    .setRepeatCount(action.repeatCount())));
    return command(session, operation, "ChallengeAutomationAction", payload.build().toByteArray());
  }

  public static NodeCommand requestAgentStateResync(
      SessionContext session,
      ExclusiveOperation operation,
      String taskId,
      String reason,
      String idempotencyKey) {
    var payload =
        RequestStateResyncCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setMode("FULL")
            .setRootRef("document")
            .setReason(reason)
            .build()
            .toByteArray();
    return new NodeCommand(
        newId("cmd_"),
        "RequestStateResync",
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        operation.operationEpoch(),
        idempotencyKey + ":" + taskId,
        payload);
  }

  public static NodeCommand agentAction(
      SessionContext session,
      ExclusiveOperation operation,
      String taskId,
      PlanStep step,
      long baseStateVersion,
      String baseContentHash) {
    var input = step.input();
    var builder =
        AgentActionCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setTaskId(taskId)
            .setStepId(step.stepId())
            .setToolId(step.toolId().name())
            .setTargetRef(input == null || input.targetRef() == null ? "" : input.targetRef())
            .setTargetRevision(
                input == null || input.targetRevision() == null ? 0 : input.targetRevision())
            .setSealedText(
                input == null || input.sealedPayload() == null ? "" : input.sealedPayload())
            .setScrollDeltaY(
                input == null || input.scrollDeltaY() == null ? 0 : input.scrollDeltaY())
            .setWaitCondition(
                input == null || input.waitCondition() == null ? "" : input.waitCondition().name())
            .setTimeoutMs(input == null || input.timeoutMs() == null ? 0 : input.timeoutMs())
            .setBaseStateVersion(baseStateVersion)
            .setBaseContentHash(baseContentHash)
            .setAllowSensitiveTarget(input != null && input.allowSensitiveTarget())
            .setMaximumAttempts(input == null ? 1 : input.maximumAttempts())
            .setStopOnError(input == null || input.stopOnError())
            .setTabId(input == null || input.tabId() == null ? "" : input.tabId())
            .setTabUrl(input == null || input.tabUrl() == null ? "" : input.tabUrl())
            .setDialogId(input == null || input.dialogId() == null ? "" : input.dialogId());
    if (input != null && !input.actions().isEmpty()) {
      builder.addAllActions(
          input.actions().stream()
              .map(
                  action ->
                      AgentActionPrimitive.newBuilder()
                          .setActionId(action.actionId())
                          .setToolId(action.toolId().name())
                          .setTargetRef(action.targetRef() == null ? "" : action.targetRef())
                          .setTargetRevision(
                              action.targetRevision() == null ? 0 : action.targetRevision())
                          .setElementId(action.elementId() == null ? "" : action.elementId())
                          .setSealedText(
                              action.sealedPayload() == null ? "" : action.sealedPayload())
                          .setScrollDeltaY(
                              action.scrollDeltaY() == null ? 0 : action.scrollDeltaY())
                          .setWaitCondition(
                              action.waitCondition() == null ? "" : action.waitCondition().name())
                          .setTimeoutMs(action.timeoutMs() == null ? 0 : action.timeoutMs())
                          .setAllowSensitiveTarget(action.allowSensitiveTarget())
                          .setMaximumAttempts(action.maximumAttempts())
                          .setTabId(action.tabId() == null ? "" : action.tabId())
                          .setTabUrl(action.tabUrl() == null ? "" : action.tabUrl())
                          .setDialogId(action.dialogId() == null ? "" : action.dialogId())
                          .build())
              .toList());
    }
    var payload = builder.build().toByteArray();
    return command(session, operation, "AgentAction", payload);
  }

  public static NodeCommand agentFileUpload(
      SessionContext session,
      ExclusiveOperation operation,
      String uploadId,
      String targetRef,
      long targetRevision,
      long baseStateVersion,
      String baseContentHash,
      String filename,
      String mimeType,
      String contentSha256,
      long contentBytes) {
    var payload =
        AgentFileUploadCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setUploadId(uploadId)
            .setTargetRef(targetRef)
            .setTargetRevision(targetRevision)
            .setBaseStateVersion(baseStateVersion)
            .setBaseContentHash(baseContentHash)
            .setFilename(filename)
            .setMimeType(mimeType)
            .setContentSha256(contentSha256)
            .setContentBytes(contentBytes)
            .build()
            .toByteArray();
    return command(session, operation, "AgentFileUpload", payload);
  }

  public static NodeCommand agentChallengeInput(
      SessionContext session,
      ExclusiveOperation operation,
      String taskId,
      String stepId,
      String targetRef,
      long targetRevision,
      String sealedText,
      long baseStateVersion,
      String baseContentHash,
      int maximumAttempts) {
    var payload =
        AgentActionCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setTaskId(taskId)
            .setStepId(stepId)
            .setToolId("TYPE_TEXT")
            .setTargetRef(targetRef)
            .setTargetRevision(targetRevision)
            .setSealedText(sealedText)
            .setBaseStateVersion(baseStateVersion)
            .setBaseContentHash(baseContentHash)
            .setAllowSensitiveTarget(true)
            .setMaximumAttempts(maximumAttempts)
            .build()
            .toByteArray();
    return command(session, operation, "AgentAction", payload);
  }

  private static NodeCommand command(
      SessionContext session, ExclusiveOperation operation, String commandType, byte[] payload) {
    return new NodeCommand(
        newId("cmd_"),
        commandType,
        session.nodeId(),
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        operation.operationEpoch(),
        operation.operationId(),
        payload);
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
