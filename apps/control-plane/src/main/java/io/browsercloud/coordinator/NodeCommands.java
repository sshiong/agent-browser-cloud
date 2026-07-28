package io.browsercloud.coordinator;

import io.browsercloud.domain.agent.AgentModels.PlanStep;
import io.browsercloud.domain.capacity.BrowserResourceBudget;
import io.browsercloud.domain.capacity.RuntimeResourceLimits;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.proto.node.v1.AdjustRuntimeResourcesCommand;
import io.browsercloud.proto.node.v1.AgentActionCommand;
import io.browsercloud.proto.node.v1.AgentNavigateCommand;
import io.browsercloud.proto.node.v1.BeginHumanTakeoverCommand;
import io.browsercloud.proto.node.v1.BusinessRecoveryActionCommand;
import io.browsercloud.proto.node.v1.EndHumanTakeoverCommand;
import io.browsercloud.proto.node.v1.ExtensionBackgroundPolicy;
import io.browsercloud.proto.node.v1.ReleaseAllInputCommand;
import io.browsercloud.proto.node.v1.RequestStateResyncCommand;
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
    return startRuntime(session, operation, requestedRuntimeBuildId, requestedLimits, null);
  }

  public static NodeCommand startRuntime(
      SessionContext session,
      ExclusiveOperation operation,
      String requestedRuntimeBuildId,
      RuntimeResourceLimits requestedLimits,
      String profileCheckpointId) {
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
            .setDesktopRequired(limits.desktop())
            .setGpuRequired(limits.gpu())
            .setNativeOsRequired(limits.nativeOs())
            .setIsolationRequired(limits.isolated())
            .setProfileCheckpointId(profileCheckpointId == null ? "" : profileCheckpointId)
            .build()
            .toByteArray();
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
        payload);
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
    var payload =
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
