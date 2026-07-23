package io.browsercloud.coordinator;

import io.browsercloud.coordinator.exceptions.InvalidSessionStateException;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session Coordinator。
 *
 * <p>负责串行处理 Session 的状态转换。 每个 Session 有且只有一个 Active Exclusive Operation。
 *
 * <p>Coordinator 不能同步等待：
 *
 * <ul>
 *   <li>Chromium 启动
 *   <li>Profile Flush
 *   <li>Snapshot 上传
 *   <li>代理探测
 *   <li>大型 State Snapshot
 * </ul>
 */
public final class SessionCoordinator {

  private static final Logger log = LoggerFactory.getLogger(SessionCoordinator.class);

  private final SessionRepository sessionRepository;
  private final OperationRepository operationRepository;
  private final NodeCommandGateway nodeCommandGateway;
  private final OutboxPublisher outboxPublisher;

  public SessionCoordinator(
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      OutboxPublisher outboxPublisher) {
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.nodeCommandGateway = nodeCommandGateway;
    this.outboxPublisher = outboxPublisher;
  }

  /**
   * 处理 Session 命令。
   *
   * @param command 要处理的命令
   * @return 处理结果
   */
  public CoordinatorResult handle(SessionCommand command) {
    return switch (command) {
      case StartSession start -> handleStart(start);
      case TerminateSession terminate -> handleTerminate(terminate);
      case NodeEventReceived event -> handleNodeEvent(event);
      case OperationTimedOut timeout -> handleTimeout(timeout);
      default -> CoordinatorResult.rejected("UNSUPPORTED_COMMAND");
    };
  }

  /**
   * 处理启动 Session 命令。
   *
   * <p>流程：
   *
   * <ol>
   *   <li>校验 Session 存在
   *   <li>确保无活跃 Operation
   *   <li>创建 StartRuntime Operation
   *   <li>发送 Node Command
   *   <li>返回 Operation ID
   * </ol>
   */
  private CoordinatorResult handleStart(StartSession command) {
    log.info("Handling start session: {}", command.sessionId());

    var session = sessionRepository.requireForUpdate(command.sessionId());
    if (session.state() != SessionState.CREATED && session.state() != SessionState.HIBERNATED) {
      throw new InvalidSessionStateException(session.sessionId(), session.state(), "start");
    }

    // 确保无活跃 Operation
    operationRepository.ensureNoActiveOperation(session.sessionId());

    // 创建 StartRuntime Operation
    var operation =
        OperationFactory.startRuntime(
            session, operationRepository.nextOperationEpoch(session.sessionId()));
    operationRepository.insert(operation);
    sessionRepository.updateWithExpectedEpoch(
        session.withState(SessionState.STARTING), session.contextEpoch());

    // 发送 Node Command
    nodeCommandGateway.send(
        NodeCommands.startRuntime(session, operation, command.requestedRuntimeBuildId()));
    outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.STARTING));

    log.info(
        "Start operation created: {} for session: {}",
        operation.operationId(),
        session.sessionId());

    return CoordinatorResult.accepted(operation.operationId());
  }

  /**
   * 处理终止 Session 命令。
   *
   * <p>流程：
   *
   * <ol>
   *   <li>校验 Session 存在
   *   <li>取消或等待当前 Operation
   *   <li>创建 Termination Operation
   *   <li>发送 StopRuntime Command
   * </ol>
   */
  private CoordinatorResult handleTerminate(TerminateSession command) {
    log.info("Handling terminate session: {}", command.sessionId());

    var session = sessionRepository.requireForUpdate(command.sessionId());
    if (session.state() == SessionState.TERMINATED) {
      throw new InvalidSessionStateException(session.sessionId(), session.state(), "terminate");
    }

    operationRepository
        .findActive(session.sessionId())
        .ifPresent(
            active ->
                operationRepository.transition(
                    active.operationId(), OperationState.ACTIVE, OperationState.ABORTED));

    // 创建 Termination Operation
    var operation =
        OperationFactory.terminate(
            session, operationRepository.nextOperationEpoch(session.sessionId()));
    operationRepository.insert(operation);
    sessionRepository.updateWithExpectedEpoch(
        session.withState(SessionState.TERMINATING), session.contextEpoch());

    // 发送 StopRuntime Command
    nodeCommandGateway.send(NodeCommands.stopRuntime(session, operation, command.reason()));
    outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.TERMINATING));

    return CoordinatorResult.accepted(operation.operationId());
  }

  /**
   * 处理 Node 事件。
   *
   * <p>流程：
   *
   * <ol>
   *   <li>校验 coordinator_term/context_epoch/operation_epoch
   *   <li>处理重复事件
   *   <li>更新 Session Context
   *   <li>提交 Operation
   *   <li>发布事件
   * </ol>
   */
  private CoordinatorResult handleNodeEvent(NodeEventReceived command) {
    log.info("Handling node event for session: {}", command.sessionId());

    var session = sessionRepository.require(command.sessionId());
    var event = command.event();

    return switch (event) {
      case NodeEvent.RuntimeStarted started -> {
        // 更新 Session Context
        var newContext =
            session
                .nextContextEpoch(
                    started.nodeId(), started.runtimeBuildId(), started.browserGeneration())
                .withState(SessionState.RUNNING);
        sessionRepository.updateWithExpectedEpoch(newContext, session.contextEpoch());

        // 提交 Operation
        operationRepository
            .findActive(session.sessionId())
            .ifPresent(
                op ->
                    operationRepository.transition(
                        op.operationId(), OperationState.ACTIVE, OperationState.COMMITTED));

        // 发布事件
        outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.RUNNING));

        yield CoordinatorResult.completed();
      }

      case NodeEvent.RuntimeStopped stopped -> {
        // 更新 Session 状态
        var newContext = session.withState(SessionState.TERMINATED);
        sessionRepository.updateWithExpectedEpoch(newContext, session.contextEpoch());

        // 提交 Operation
        operationRepository
            .findActive(session.sessionId())
            .ifPresent(
                op ->
                    operationRepository.transition(
                        op.operationId(), OperationState.ACTIVE, OperationState.COMMITTED));

        yield CoordinatorResult.completed();
      }

      case NodeEvent.RuntimeCrashed crashed -> {
        // 更新 Session 状态为 DEGRADED
        var newContext = session.withState(SessionState.DEGRADED);
        sessionRepository.updateWithExpectedEpoch(newContext, session.contextEpoch());

        // 发布事件
        outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.DEGRADED));

        yield CoordinatorResult.completed();
      }

      case NodeEvent.StateUpdated updated -> {
        // 状态更新，不需要修改 Session Context
        yield CoordinatorResult.completed();
      }
    };
  }

  /**
   * 处理 Operation 超时。
   *
   * <p>流程：
   *
   * <ol>
   *   <li>Abort 当前 Operation
   *   <li>触发补偿或 Recovery
   * </ol>
   */
  private CoordinatorResult handleTimeout(OperationTimedOut timeout) {
    log.warn(
        "Handling operation timeout: {} for session: {}",
        timeout.operationId(),
        timeout.sessionId());

    // Abort Operation
    operationRepository.transition(
        timeout.operationId(), OperationState.ACTIVE, OperationState.TIMED_OUT);

    // 发布事件
    outboxPublisher.append(new OperationTimedOutEvent(timeout.sessionId(), timeout.operationId()));

    return CoordinatorResult.completed();
  }
}
