package io.browsercloud.coordinator;

import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
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

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
