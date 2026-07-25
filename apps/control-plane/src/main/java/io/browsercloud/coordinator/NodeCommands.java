package io.browsercloud.coordinator;

import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.proto.node.v1.BeginHumanTakeoverCommand;
import io.browsercloud.proto.node.v1.EndHumanTakeoverCommand;
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
      SessionContext session, ExclusiveOperation operation, String requestedRuntimeBuildId) {
    var payload =
        StartRuntimeCommand.newBuilder()
            .setSessionId(session.sessionId())
            .setRuntimeBuildId(requestedRuntimeBuildId)
            .setProfileId(session.profileId())
            .setDisplay("")
            .setCdpPort(0)
            .setProxyBindingId(session.proxyBindingId() == null ? "" : session.proxyBindingId())
            .build()
            .toByteArray();
    return new NodeCommand(
        newId("cmd_"),
        "StartRuntime",
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        operation.operationEpoch(),
        operation.operationId(),
        payload);
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
        session.sessionId(),
        session.tenantId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        0,
        idempotencyKey,
        payload);
  }

  private static NodeCommand command(
      SessionContext session, ExclusiveOperation operation, String commandType, byte[] payload) {
    return new NodeCommand(
        newId("cmd_"),
        commandType,
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
