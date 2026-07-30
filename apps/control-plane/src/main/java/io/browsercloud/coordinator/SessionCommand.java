package io.browsercloud.coordinator;

/**
 * Session 命令密封接口。
 *
 * <p>所有 Session 状态变更必须通过命令驱动，由 Session Coordinator 串行处理。
 */
public sealed interface SessionCommand
    permits StartSession,
        TerminateSession,
        HibernateSession,
        CleanupMigrationTarget,
        SubmitAgentAction,
        ReconcileAgentExecution,
        RequestHumanTakeover,
        ReleaseHumanTakeover,
        NodeEventReceived,
        WorkflowCompleted,
        OperationTimedOut {

  /** 获取目标 Session ID。 */
  String sessionId();
}
