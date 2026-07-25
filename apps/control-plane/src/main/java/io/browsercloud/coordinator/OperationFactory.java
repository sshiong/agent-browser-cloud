package io.browsercloud.coordinator;

import io.browsercloud.domain.operation.*;
import io.browsercloud.domain.session.SessionContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Operation 工厂。
 *
 * <p>负责创建各种类型的 ExclusiveOperation。
 */
public final class OperationFactory {

  private OperationFactory() {}

  /** 创建 StartRuntime Operation。 */
  public static ExclusiveOperation startRuntime(SessionContext session, long operationEpoch) {
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.SYSTEM,
        "control-plane",
        OperationMode.AGENT_INTERACTIVE,
        0,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        true,
        true,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of(),
        Instant.now().plusSeconds(300),
        Instant.now(),
        null);
  }

  /** 创建 Terminate Operation。 */
  public static ExclusiveOperation terminate(SessionContext session, long operationEpoch) {
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.SYSTEM,
        "control-plane",
        OperationMode.TERMINATION,
        100,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of(),
        Instant.now().plusSeconds(60),
        Instant.now(),
        null);
  }

  /** 创建 Browser Crash Recovery Operation。 */
  public static ExclusiveOperation recovery(SessionContext session, long operationEpoch) {
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.SYSTEM,
        "browser-supervisor",
        OperationMode.RECOVERY,
        95,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of("runtime.restart", "state.resync"),
        Instant.now().plusSeconds(120),
        Instant.now(),
        null);
  }

  /** 创建 HumanTakeover Operation。 */
  public static ExclusiveOperation humanTakeover(
      SessionContext session, String userId, long operationEpoch) {
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.HUMAN,
        userId,
        OperationMode.HUMAN_TAKEOVER,
        90,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        true,
        false,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of(),
        Instant.now().plusSeconds(3600),
        Instant.now(),
        null);
  }
}
