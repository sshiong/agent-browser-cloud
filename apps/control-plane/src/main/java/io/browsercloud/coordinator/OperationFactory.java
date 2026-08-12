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

  /** Create a checkpoint-backed hibernation operation. */
  public static ExclusiveOperation hibernate(SessionContext session, long operationEpoch) {
    var now = Instant.now();
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.SYSTEM,
        "resource-decision-engine",
        OperationMode.HIBERNATE,
        80,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of("profile.checkpoint", "runtime.stop"),
        now.plusSeconds(180),
        now,
        null);
  }

  /** Stop a possibly-started target and return the Session to HIBERNATED before retry. */
  public static ExclusiveOperation migrationCleanup(SessionContext session, long operationEpoch) {
    var now = Instant.now();
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.SYSTEM,
        "session-migration-workflow",
        OperationMode.MIGRATION_CLEANUP,
        100,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.EXECUTING,
        OperationState.ACTIVE,
        Set.of("runtime.stop", "migration.target.cleanup"),
        now.plusSeconds(60),
        now,
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

  /** Creates a short-lived, non-retryable single-click Human Assist Operation. */
  public static ExclusiveOperation humanAssist(
      SessionContext session, String userId, long operationEpoch) {
    var now = Instant.now();
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.HUMAN,
        userId,
        OperationMode.HUMAN_ASSIST,
        85,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.EXECUTING,
        OperationState.ACTIVE,
        Set.of("challenge.click.once"),
        now.plusSeconds(30),
        now,
        null);
  }

  /** 创建受限 Agent Task Operation。 */
  public static ExclusiveOperation agentTask(
      SessionContext session, String taskId, long operationEpoch, Set<String> capabilities) {
    return new ExclusiveOperation(
        "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        session.sessionId(),
        OwnerType.AGENT,
        taskId,
        OperationMode.AGENT_INTERACTIVE,
        40,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        true,
        true,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.copyOf(capabilities),
        Instant.now().plusSeconds(120),
        Instant.now(),
        null);
  }

  /** 创建已同步提交的资源策略 Operation。策略写入 PostgreSQL 后即完成。 */
  public static ExclusiveOperation committedResourceAdjustment(
      SessionContext session, String actorId, long operationEpoch, String operationId) {
    var now = Instant.now();
    return new ExclusiveOperation(
        operationId,
        session.sessionId(),
        OwnerType.SYSTEM,
        actorId,
        OperationMode.RESOURCE_ADJUSTMENT,
        20,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.COMPLETING,
        OperationState.COMMITTED,
        Set.of("resource.policy"),
        now.plusSeconds(60),
        now,
        now);
  }

  /** Creates an audit-distinct committed Operation for a verified late resource ACK. */
  public static ExclusiveOperation committedResourceReconciliation(
      SessionContext session, long operationEpoch, String operationId) {
    var now = Instant.now();
    return new ExclusiveOperation(
        operationId,
        session.sessionId(),
        OwnerType.SYSTEM,
        "resource-late-ack-reconciler",
        OperationMode.RESOURCE_ADJUSTMENT,
        20,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.COMPLETING,
        OperationState.COMMITTED,
        Set.of("resource.reconcile"),
        now.plusSeconds(60),
        now,
        now);
  }

  /** 创建已同步提交的 Session Application Contract Rebind Operation。 */
  public static ExclusiveOperation committedApplicationBinding(
      SessionContext session, String actorId, long operationEpoch, String operationId) {
    var now = Instant.now();
    return new ExclusiveOperation(
        operationId,
        session.sessionId(),
        OwnerType.HUMAN,
        actorId,
        OperationMode.APPLICATION_BINDING,
        30,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.COMPLETING,
        OperationState.COMMITTED,
        Set.of("recovery.contract.rebind"),
        now.plusSeconds(60),
        now,
        now);
  }

  /** 创建等待 Browser Node cgroup ACK 的在线资源调整 Operation。 */
  public static ExclusiveOperation resourceAdjustment(
      SessionContext session, long operationEpoch, String operationId) {
    var now = Instant.now();
    return new ExclusiveOperation(
        operationId,
        session.sessionId(),
        OwnerType.SYSTEM,
        "resource-decision-engine",
        OperationMode.RESOURCE_ADJUSTMENT,
        20,
        session.coordinatorTerm(),
        session.contextEpoch(),
        operationEpoch,
        null,
        false,
        false,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of("resource.adjust"),
        now.plusSeconds(90),
        now,
        null);
  }
}
