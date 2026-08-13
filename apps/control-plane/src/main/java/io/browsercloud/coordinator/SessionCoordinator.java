package io.browsercloud.coordinator;

import io.browsercloud.coordinator.exceptions.ActiveOperationExistsException;
import io.browsercloud.coordinator.exceptions.InvalidSessionStateException;
import io.browsercloud.coordinator.exceptions.StaleOperationException;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.operation.OwnerType;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
  private static final long MAX_RECOVERY_ATTEMPTS_PER_HOUR = 3;

  private final SessionRepository sessionRepository;
  private final OperationRepository operationRepository;
  private final NodeCommandGateway nodeCommandGateway;
  private final OutboxPublisher outboxPublisher;
  private final CoordinatorOwnershipService ownershipService;
  private final CoordinatorReconciliationMetrics reconciliationMetrics;
  private final RuntimeResourceLimitsRepository resourceLimitsRepository;
  private final ProxyRuntimeBindingRepository proxyBindingRepository;
  private final BrowserTransactionPolicyRepository browserTransactionPolicyRepository;
  private final CoordinatorRouteAuthority routeAuthority;
  private final CoordinatorShardLocality shardLocality;

  public SessionCoordinator(
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      OutboxPublisher outboxPublisher,
      CoordinatorOwnershipService ownershipService,
      CoordinatorReconciliationMetrics reconciliationMetrics,
      RuntimeResourceLimitsRepository resourceLimitsRepository,
      ProxyRuntimeBindingRepository proxyBindingRepository,
      BrowserTransactionPolicyRepository browserTransactionPolicyRepository,
      CoordinatorRouteAuthority routeAuthority,
      CoordinatorShardLocality shardLocality) {
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.nodeCommandGateway = nodeCommandGateway;
    this.outboxPublisher = outboxPublisher;
    this.ownershipService = ownershipService;
    this.reconciliationMetrics = reconciliationMetrics;
    this.resourceLimitsRepository = resourceLimitsRepository;
    this.proxyBindingRepository = proxyBindingRepository;
    this.browserTransactionPolicyRepository = browserTransactionPolicyRepository;
    this.routeAuthority = routeAuthority;
    this.shardLocality = shardLocality;
  }

  /** N-1 source compatibility for callers that predate Browser transaction Site Policy. */
  public SessionCoordinator(
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      OutboxPublisher outboxPublisher,
      CoordinatorOwnershipService ownershipService,
      CoordinatorReconciliationMetrics reconciliationMetrics,
      RuntimeResourceLimitsRepository resourceLimitsRepository,
      ProxyRuntimeBindingRepository proxyBindingRepository,
      CoordinatorRouteAuthority routeAuthority,
      CoordinatorShardLocality shardLocality) {
    this(
        sessionRepository,
        operationRepository,
        nodeCommandGateway,
        outboxPublisher,
        ownershipService,
        reconciliationMetrics,
        resourceLimitsRepository,
        proxyBindingRepository,
        (sessionId, tenantId) -> BrowserTransactionPolicy.empty(),
        routeAuthority,
        shardLocality);
  }

  /** Compatibility constructor for isolated domain tests without physical worker membership. */
  public SessionCoordinator(
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      OutboxPublisher outboxPublisher,
      CoordinatorOwnershipService ownershipService,
      CoordinatorReconciliationMetrics reconciliationMetrics,
      RuntimeResourceLimitsRepository resourceLimitsRepository,
      CoordinatorRouteAuthority routeAuthority) {
    this(
        sessionRepository,
        operationRepository,
        nodeCommandGateway,
        outboxPublisher,
        ownershipService,
        reconciliationMetrics,
        resourceLimitsRepository,
        (sessionId, bindingId) -> Optional.empty(),
        (sessionId, tenantId) -> BrowserTransactionPolicy.empty(),
        routeAuthority,
        ignored -> true);
  }

  /** Compatibility constructor for tests that supply physical shard membership explicitly. */
  public SessionCoordinator(
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      OutboxPublisher outboxPublisher,
      CoordinatorOwnershipService ownershipService,
      CoordinatorReconciliationMetrics reconciliationMetrics,
      RuntimeResourceLimitsRepository resourceLimitsRepository,
      CoordinatorRouteAuthority routeAuthority,
      CoordinatorShardLocality shardLocality) {
    this(
        sessionRepository,
        operationRepository,
        nodeCommandGateway,
        outboxPublisher,
        ownershipService,
        reconciliationMetrics,
        resourceLimitsRepository,
        (sessionId, bindingId) -> Optional.empty(),
        (sessionId, tenantId) -> BrowserTransactionPolicy.empty(),
        routeAuthority,
        shardLocality);
  }

  /**
   * 处理 Session 命令。
   *
   * @param command 要处理的命令
   * @return 处理结果
   */
  public CoordinatorResult handle(SessionCommand command) {
    var route = routeAuthority.resolve(command.sessionId());
    if (!(command instanceof NodeEventReceived)
        && !shardLocality.owns(route.shardId())
        && !ownershipService.isCurrentOwner(command.sessionId(), route.routeEpoch())) {
      throw new CoordinatorShardNotLocalException(
          command.sessionId(), route.routeEpoch(), route.shardId());
    }
    // Keep the global lock order aligned with route migration:
    // Session row -> Coordinator ownership. Handler-specific lookups reuse this row lock.
    sessionRepository.lockForUpdate(command.sessionId());
    if (command instanceof NodeEventReceived event) {
      ownershipService.assertCurrentGeneration(
          event.sessionId(), event.coordinatorTerm(), route.routeEpoch());
    } else {
      long currentTerm = ownershipService.acquireSession(command.sessionId(), route.routeEpoch());
      var reconciliation =
          reconciliationMetrics.record(() -> reconcileStaleOperation(command, currentTerm));
      if (reconciliation.isPresent()) {
        return reconciliation.orElseThrow();
      }
    }
    return switch (command) {
      case StartSession start -> handleStart(start);
      case TerminateSession terminate -> handleTerminate(terminate);
      case HibernateSession hibernate -> handleHibernate(hibernate);
      case CleanupMigrationTarget cleanup -> handleMigrationTargetCleanup(cleanup);
      case RequestHumanTakeover takeover -> handleHumanTakeover(takeover);
      case ReleaseHumanTakeover release -> handleReleaseHumanTakeover(release);
      case ReconcileAgentExecution ignored -> CoordinatorResult.completed();
      case NodeEventReceived event -> handleNodeEvent(event);
      case OperationTimedOut timeout -> handleTimeout(timeout);
      default -> CoordinatorResult.rejected("UNSUPPORTED_COMMAND");
    };
  }

  public static final class CoordinatorShardNotLocalException extends RuntimeException {
    public CoordinatorShardNotLocalException(String sessionId, long routeEpoch, int shardId) {
      super("COORDINATOR_SHARD_NOT_LOCAL:" + sessionId + ":" + routeEpoch + ":" + shardId);
    }
  }

  /**
   * 在 Ownership Term 提升后处理旧世代未完成的 Operation。
   *
   * <p>启动、恢复和终止属于 Runtime 生命周期边界，无法在不知道旧命令是否执行的情况下安全重放； 新 Coordinator 因此统一创建新 term 的 Termination
   * Operation，让 Node 幂等停止并释放 Profile、 Proxy 和输入资源。运行态的 HumanTakeover 则按当前用户请求重建
   * barrier；其他运行态操作先释放全部输入， 再由当前命令继续。
   */
  private Optional<CoordinatorResult> reconcileStaleOperation(
      SessionCommand command, long currentTerm) {
    if (currentTerm <= 0) {
      return Optional.empty();
    }
    var session = sessionRepository.requireForUpdate(command.sessionId());
    var stale =
        operationRepository
            .findActive(session.sessionId())
            .filter(operation -> operation.coordinatorTerm() < currentTerm);
    if (stale.isEmpty()) {
      return Optional.empty();
    }

    var staleOperation = stale.orElseThrow();
    var fencedSession = session.withCoordinatorTerm(currentTerm);
    operationRepository.transition(
        staleOperation.operationId(), OperationState.ACTIVE, OperationState.ABORTED);
    reconciliationMetrics.staleOperationAborted();
    log.warn(
        "Aborted stale operation {} for session {} after coordinator term advanced {} -> {}",
        staleOperation.operationId(),
        session.sessionId(),
        staleOperation.coordinatorTerm(),
        currentTerm);

    if (session.state() == SessionState.STARTING
        || session.state() == SessionState.RECOVERING
        || session.state() == SessionState.TERMINATING) {
      reconciliationMetrics.cleanupStarted();
      try {
        var cleanup =
            OperationFactory.terminate(
                fencedSession, operationRepository.nextOperationEpoch(session.sessionId()));
        operationRepository.insert(cleanup);
        sessionRepository.updateWithExpectedEpoch(
            fencedSession.withState(SessionState.TERMINATING), session.contextEpoch());
        nodeCommandGateway.send(
            NodeCommands.stopRuntime(fencedSession, cleanup, "coordinator_failover"));
        outboxPublisher.append(
            new SessionStateChanged(session.sessionId(), SessionState.TERMINATING));
        return Optional.of(CoordinatorResult.accepted(cleanup.operationId()));
      } catch (RuntimeException | Error failure) {
        reconciliationMetrics.cleanupFailed();
        throw failure;
      }
    }

    if (staleOperation.mode() == OperationMode.HUMAN_TAKEOVER
        && command instanceof RequestHumanTakeover takeover
        && takeover.userId().equals(staleOperation.actorId())) {
      var replacement =
          OperationFactory.humanTakeover(
              fencedSession,
              takeover.userId(),
              operationRepository.nextOperationEpoch(session.sessionId()));
      operationRepository.insert(replacement);
      nodeCommandGateway.send(NodeCommands.beginHumanTakeover(fencedSession, replacement));
      return Optional.of(CoordinatorResult.accepted(replacement.operationId()));
    }

    if (staleOperation.mode() == OperationMode.HUMAN_TAKEOVER
        && command instanceof ReleaseHumanTakeover release
        && release.userId().equals(staleOperation.actorId())) {
      var replacement =
          OperationFactory.humanTakeover(
                  fencedSession,
                  release.userId(),
                  operationRepository.nextOperationEpoch(session.sessionId()))
              .withPhase(OperationPhase.COMPLETING);
      operationRepository.insert(replacement);
      nodeCommandGateway.send(NodeCommands.endHumanTakeover(fencedSession, replacement));
      return Optional.of(CoordinatorResult.accepted(replacement.operationId()));
    }

    nodeCommandGateway.send(
        NodeCommands.releaseAllInput(
            fencedSession, staleOperation, "coordinator_failover_stale_operation"));
    if (command instanceof OperationTimedOut timeout
        && timeout.operationId().equals(staleOperation.operationId())) {
      outboxPublisher.append(
          new OperationTimedOutEvent(timeout.sessionId(), timeout.operationId()));
      return Optional.of(CoordinatorResult.completed());
    }
    return Optional.empty();
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
        NodeCommands.startRuntime(
            session,
            operation,
            command.requestedRuntimeBuildId(),
            command.resourceLimits(),
            command.profileCheckpointId(),
            proxyBindingRepository.find(session.sessionId(), session.proxyBindingId()).orElse(null),
            browserTransactionPolicyRepository.find(session.sessionId(), session.tenantId())));
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

  private CoordinatorResult handleHibernate(HibernateSession command) {
    var session = sessionRepository.requireForUpdate(command.sessionId());
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new InvalidSessionStateException(session.sessionId(), session.state(), "hibernate");
    }
    operationRepository.ensureNoActiveOperation(session.sessionId());
    var operation =
        OperationFactory.hibernate(
            session, operationRepository.nextOperationEpoch(session.sessionId()));
    operationRepository.insert(operation);
    sessionRepository.updateWithExpectedEpoch(
        session.withState(SessionState.HIBERNATING), session.contextEpoch());
    nodeCommandGateway.send(NodeCommands.stopRuntime(session, operation, command.reason()));
    outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.HIBERNATING));
    return CoordinatorResult.accepted(operation.operationId());
  }

  private CoordinatorResult handleMigrationTargetCleanup(CleanupMigrationTarget command) {
    var session = sessionRepository.requireForUpdate(command.sessionId());
    if (session.state() != SessionState.STARTING && session.state() != SessionState.FAILED) {
      throw new InvalidSessionStateException(
          session.sessionId(), session.state(), "migration target cleanup");
    }
    operationRepository
        .findActive(session.sessionId())
        .ifPresent(
            active ->
                operationRepository.transition(
                    active.operationId(), OperationState.ACTIVE, OperationState.ABORTED));
    var cleanup =
        OperationFactory.migrationCleanup(
            session, operationRepository.nextOperationEpoch(session.sessionId()));
    operationRepository.insert(cleanup);
    sessionRepository.updateWithExpectedEpoch(
        session.withState(SessionState.HIBERNATING), session.contextEpoch());
    nodeCommandGateway.send(NodeCommands.stopRuntime(session, cleanup, command.reason()));
    outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.HIBERNATING));
    return CoordinatorResult.accepted(cleanup.operationId());
  }

  private CoordinatorResult handleHumanTakeover(RequestHumanTakeover command) {
    var session = sessionRepository.requireForUpdate(command.sessionId());
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new InvalidSessionStateException(session.sessionId(), session.state(), "takeover");
    }
    var active = operationRepository.findActive(session.sessionId());
    if (active.isPresent()) {
      var operation = active.orElseThrow();
      // A remote human joining the desktop is a collaborative observer/controller, not a
      // replacement for the running Agent. Keep the Agent Operation and let Browser Node's
      // short human-input priority window arbitrate actual conflicting writes.
      if (operation.ownerType() == OwnerType.AGENT
          || operation.mode() == OperationMode.AGENT_INTERACTIVE) {
        return CoordinatorResult.accepted(operation.operationId());
      }
      if (operation.mode() == OperationMode.HUMAN_TAKEOVER
          && command.userId().equals(operation.actorId())) {
        return CoordinatorResult.accepted(operation.operationId());
      }
      if (!operation.preemptible() || operation.priority() >= 90) {
        throw new ActiveOperationExistsException(session.sessionId(), operation.operationId());
      }
      operationRepository.transition(
          operation.operationId(), OperationState.ACTIVE, OperationState.ABORTED);
    }

    var takeover =
        OperationFactory.humanTakeover(
            session, command.userId(), operationRepository.nextOperationEpoch(session.sessionId()));
    operationRepository.insert(takeover);
    nodeCommandGateway.send(NodeCommands.beginHumanTakeover(session, takeover));
    return CoordinatorResult.accepted(takeover.operationId());
  }

  private CoordinatorResult handleReleaseHumanTakeover(ReleaseHumanTakeover command) {
    var session = sessionRepository.requireForUpdate(command.sessionId());
    var operation =
        operationRepository
            .findActive(session.sessionId())
            .filter(active -> active.mode() == OperationMode.HUMAN_TAKEOVER)
            .filter(active -> command.userId().equals(active.actorId()))
            .orElseThrow(
                () ->
                    new StaleOperationException(
                        session.sessionId(), "ACTIVE_HUMAN_TAKEOVER", "NOT_FOUND"));
    if (operation.phase() == OperationPhase.COMPLETING) {
      return CoordinatorResult.accepted(operation.operationId());
    }
    operationRepository.transitionPhase(
        operation.operationId(), operation.phase(), OperationPhase.COMPLETING);
    nodeCommandGateway.send(NodeCommands.endHumanTakeover(session, operation));
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
    log.info(
        "Handling node event {} for session: {}",
        command.event().getClass().getSimpleName(),
        command.sessionId());

    var session = sessionRepository.requireForUpdate(command.sessionId());
    var event = command.event();

    if (!command.sessionId().equals(eventSessionId(event))) {
      return CoordinatorResult.rejected("SESSION_ID_MISMATCH");
    }
    if (!command.tenantId().equals(session.tenantId())) {
      return CoordinatorResult.rejected("TENANT_MISMATCH");
    }
    if (command.coordinatorTerm() != session.coordinatorTerm()) {
      return CoordinatorResult.rejected("STALE_COORDINATOR_TERM");
    }
    if (event instanceof NodeEvent.ProfileWarmTierSynced
        && command.contextEpoch() != session.contextEpoch()) {
      // A completed delta from a previous Browser context must never block the durable Node
      // journal after a migration/restart. Its Region-local barrier remains valid, but the new
      // context owns all subsequent Profile writes and will establish its own Warm Tier cursor.
      return CoordinatorResult.rejected("STALE_PROFILE_WARM_TIER_CONTEXT");
    }
    if (command.contextEpoch() != session.contextEpoch()) {
      return CoordinatorResult.rejected("STALE_CONTEXT_EPOCH");
    }
    if (command.sequence() <= 0) {
      return CoordinatorResult.rejected("INVALID_EVENT_SEQUENCE");
    }

    return switch (event) {
      case NodeEvent.RuntimeStarted started -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (session.state() != SessionState.STARTING
            && session.state() != SessionState.RECOVERING) {
          yield CoordinatorResult.rejected("INVALID_SESSION_STATE");
        }
        if (operation.isEmpty()) {
          yield CoordinatorResult.rejected("STALE_OPERATION_EPOCH");
        }
        if (session.state() == SessionState.RECOVERING
            && operation.orElseThrow().mode() != OperationMode.RECOVERY) {
          yield CoordinatorResult.rejected("INVALID_RECOVERY_OPERATION");
        }
        if (started.browserGeneration() <= session.browserGeneration()) {
          yield CoordinatorResult.rejected("STALE_BROWSER_GENERATION");
        }

        // 更新 Session Context
        var newContext =
            session
                .nextContextEpoch(
                    started.nodeId(), started.runtimeBuildId(), started.browserGeneration())
                .withState(SessionState.RUNNING);
        sessionRepository.updateWithExpectedEpoch(newContext, session.contextEpoch());

        // 提交 Operation
        operationRepository.transition(
            operation.orElseThrow().operationId(), OperationState.ACTIVE, OperationState.COMMITTED);

        // 发布事件
        outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.RUNNING));

        yield CoordinatorResult.completed();
      }

      case NodeEvent.RuntimeStopped stopped -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (session.state() != SessionState.TERMINATING
            && session.state() != SessionState.HIBERNATING) {
          yield CoordinatorResult.rejected("INVALID_SESSION_STATE");
        }
        if (operation.isEmpty()) {
          yield CoordinatorResult.rejected("STALE_OPERATION_EPOCH");
        }
        var hibernating = session.state() == SessionState.HIBERNATING;
        if (hibernating
            && operation.orElseThrow().mode() != OperationMode.HIBERNATE
            && operation.orElseThrow().mode() != OperationMode.MIGRATION_CLEANUP) {
          yield CoordinatorResult.rejected("INVALID_HIBERNATE_OPERATION");
        }

        // 更新 Session 状态
        var nextState = hibernating ? SessionState.HIBERNATED : SessionState.TERMINATED;
        var newContext = session.withState(nextState);
        sessionRepository.updateWithExpectedEpoch(newContext, session.contextEpoch());

        // 提交 Operation
        operationRepository.transition(
            operation.orElseThrow().operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
        outboxPublisher.append(new SessionStateChanged(session.sessionId(), nextState));

        yield CoordinatorResult.completed();
      }

      case NodeEvent.RuntimeResourcesAdjusted adjusted -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
          yield CoordinatorResult.rejected("INVALID_SESSION_STATE");
        }
        if (operation.isEmpty()
            || operation.orElseThrow().mode() != OperationMode.RESOURCE_ADJUSTMENT
            || !operation.orElseThrow().operationId().equals(adjusted.operationId())) {
          yield CoordinatorResult.rejected("STALE_RESOURCE_OPERATION");
        }
        if (!session.nodeId().equals(adjusted.nodeId())) {
          yield CoordinatorResult.rejected("RESOURCE_NODE_MISMATCH");
        }
        var resourceOperation = operation.orElseThrow();
        if (resourceOperation.phase() != OperationPhase.PREPARING
            && resourceOperation.phase() != OperationPhase.EXECUTING
            && resourceOperation.phase() != OperationPhase.VERIFYING
            && resourceOperation.phase() != OperationPhase.COMPLETING) {
          yield CoordinatorResult.rejected("INVALID_RESOURCE_OPERATION_PHASE");
        }
        // Resource Application Service validates the old/new allocation and commits Placement,
        // Policy, lifecycle ledger and this Operation atomically after this fencing check.
        yield CoordinatorResult.completed();
      }

      case NodeEvent.ProfileWarmTierSynced synced -> {
        // The sync can finish concurrently with StopRuntime. Accept the already committed barrier
        // while this Browser context still owns the Profile instead of turning it into a poison
        // journal event merely because the Session entered TERMINATING/HIBERNATING first.
        if (command.operationEpoch() != 0
            || !session.nodeId().equals(synced.nodeId())
            || !session.profileId().equals(synced.profileId())) {
          yield CoordinatorResult.rejected("INVALID_PROFILE_WARM_TIER_EVENT");
        }
        yield CoordinatorResult.completed();
      }

      case NodeEvent.RuntimeCrashed crashed -> {
        if (session.state() == SessionState.TERMINATING
            || session.state() == SessionState.TERMINATED) {
          yield CoordinatorResult.rejected("RECOVERY_SUPPRESSED_DURING_TERMINATION");
        }
        if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
          yield CoordinatorResult.rejected("INVALID_SESSION_STATE");
        }

        operationRepository
            .findActive(session.sessionId())
            .ifPresent(
                active ->
                    operationRepository.transition(
                        active.operationId(), OperationState.ACTIVE, OperationState.ABORTED));

        var recentAttempts =
            operationRepository.countSince(
                session.sessionId(),
                OperationMode.RECOVERY,
                Instant.now().minus(1, ChronoUnit.HOURS));
        if (recentAttempts >= MAX_RECOVERY_ATTEMPTS_PER_HOUR
            || session.runtimeBuildId() == null
            || session.runtimeBuildId().isBlank()) {
          sessionRepository.updateWithExpectedEpoch(
              session.withState(SessionState.FAILED), session.contextEpoch());
          outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.FAILED));
          yield CoordinatorResult.completed();
        }

        var recovery =
            OperationFactory.recovery(
                session, operationRepository.nextOperationEpoch(session.sessionId()));
        operationRepository.insert(recovery);
        sessionRepository.updateWithExpectedEpoch(
            session.withState(SessionState.RECOVERING), session.contextEpoch());
        nodeCommandGateway.send(
            NodeCommands.startRuntime(
                session,
                recovery,
                session.runtimeBuildId(),
                resourceLimitsRepository.require(session.sessionId()),
                null,
                proxyBindingRepository
                    .find(session.sessionId(), session.proxyBindingId())
                    .orElse(null),
                browserTransactionPolicyRepository.find(session.sessionId(), session.tenantId())));
        outboxPublisher.append(
            new SessionStateChanged(session.sessionId(), SessionState.RECOVERING));

        yield CoordinatorResult.completed();
      }

      case NodeEvent.StateUpdated updated -> {
        if (command.operationEpoch() != 0) {
          var operation = matchingActiveOperation(session.sessionId(), command);
          if (operation.isEmpty()) {
            yield CoordinatorResult.rejected("STALE_OPERATION");
          }
          var active = operation.orElseThrow();
          var agentState =
              active.ownerType() == OwnerType.AGENT
                  && active.mode() == OperationMode.AGENT_INTERACTIVE;
          var humanAssistState =
              active.ownerType() == OwnerType.HUMAN
                  && active.mode() == OperationMode.HUMAN_ASSIST
                  && "HUMAN_ASSIST".equals(updated.snapshotKind());
          if (!agentState && !humanAssistState) {
            yield CoordinatorResult.rejected("STALE_OPERATION");
          }
        }
        // 状态更新不修改 Session Context；Agent 状态回调已绑定当前 Operation。
        yield CoordinatorResult.completed();
      }
      case NodeEvent.StateSnapshotBegin ignored -> CoordinatorResult.completed();
      case NodeEvent.StateSnapshotChunk ignored -> CoordinatorResult.completed();
      case NodeEvent.StateSnapshotCommit ignored -> CoordinatorResult.completed();
      case NodeEvent.StateDiff diff -> CoordinatorResult.completed();
      case NodeEvent.DiffTruncated truncated -> CoordinatorResult.completed();
      case NodeEvent.AgentNavigationFailed failed -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (operation.isEmpty()
            || operation.orElseThrow().ownerType() != OwnerType.AGENT
            || operation.orElseThrow().mode() != OperationMode.AGENT_INTERACTIVE
            || !operation.orElseThrow().actorId().equals(failed.taskId())) {
          yield CoordinatorResult.rejected("STALE_AGENT_OPERATION");
        }
        yield CoordinatorResult.completed();
      }
      case NodeEvent.AgentActionFailed failed -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (operation.isEmpty()
            || operation.orElseThrow().ownerType() != OwnerType.AGENT
            || operation.orElseThrow().mode() != OperationMode.AGENT_INTERACTIVE
            || !operation.orElseThrow().actorId().equals(failed.taskId())) {
          yield CoordinatorResult.rejected("STALE_AGENT_OPERATION");
        }
        yield CoordinatorResult.completed();
      }
      case NodeEvent.HumanAssistFailed failed -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (operation.isEmpty()
            || operation.orElseThrow().ownerType() != OwnerType.HUMAN
            || operation.orElseThrow().mode() != OperationMode.HUMAN_ASSIST) {
          yield CoordinatorResult.rejected("STALE_HUMAN_ASSIST");
        }
        yield CoordinatorResult.completed();
      }
      case NodeEvent.RemoteDesktopParticipantChanged ignored -> {
        if (command.operationEpoch() != 0
            || (session.state() != SessionState.RUNNING
                && session.state() != SessionState.DEGRADED)) {
          yield CoordinatorResult.rejected("INVALID_REMOTE_DESKTOP_EVENT");
        }
        yield CoordinatorResult.completed();
      }
      case NodeEvent.EvidenceCaptured ignored -> {
        if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
          yield CoordinatorResult.rejected("INVALID_SESSION_STATE");
        }
        yield CoordinatorResult.completed();
      }
      case NodeEvent.HumanTakeoverReady ready -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (operation.isEmpty()
            || operation.orElseThrow().mode() != OperationMode.HUMAN_TAKEOVER
            || !operation.orElseThrow().actorId().equals(ready.userId())) {
          yield CoordinatorResult.rejected("STALE_HUMAN_TAKEOVER");
        }
        if (operation.orElseThrow().phase() == OperationPhase.PREPARING) {
          operationRepository.transitionPhase(
              operation.orElseThrow().operationId(),
              OperationPhase.PREPARING,
              OperationPhase.EXECUTING);
        }
        yield CoordinatorResult.completed();
      }
      case NodeEvent.HumanTakeoverEnded ended -> {
        var operation = matchingActiveOperation(session.sessionId(), command);
        if (operation.isEmpty()
            || operation.orElseThrow().mode() != OperationMode.HUMAN_TAKEOVER
            || !operation.orElseThrow().actorId().equals(ended.userId())) {
          yield CoordinatorResult.rejected("STALE_HUMAN_TAKEOVER");
        }
        var active = operation.orElseThrow();
        if (active.phase() == OperationPhase.EXECUTING
            && ended.reason().equals("GATEWAY_DISCONNECT")) {
          operationRepository.transitionPhase(
              active.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
        } else if (active.phase() != OperationPhase.COMPLETING) {
          yield CoordinatorResult.rejected("STALE_HUMAN_TAKEOVER");
        }
        operationRepository.transition(
            active.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
        yield CoordinatorResult.completed();
      }
    };
  }

  private Optional<ExclusiveOperation> matchingActiveOperation(
      String sessionId, NodeEventReceived command) {
    return operationRepository
        .findActive(sessionId)
        .filter(operation -> operation.coordinatorTerm() == command.coordinatorTerm())
        .filter(operation -> operation.contextEpoch() == command.contextEpoch())
        .filter(operation -> operation.operationEpoch() == command.operationEpoch());
  }

  private String eventSessionId(NodeEvent event) {
    return switch (event) {
      case NodeEvent.RuntimeStarted started -> started.sessionId();
      case NodeEvent.RuntimeStopped stopped -> stopped.sessionId();
      case NodeEvent.ProfileWarmTierSynced synced -> synced.sessionId();
      case NodeEvent.RuntimeResourcesAdjusted adjusted -> adjusted.sessionId();
      case NodeEvent.RuntimeCrashed crashed -> crashed.sessionId();
      case NodeEvent.StateUpdated updated -> updated.sessionId();
      case NodeEvent.StateSnapshotBegin begin -> begin.sessionId();
      case NodeEvent.StateSnapshotChunk chunk -> chunk.sessionId();
      case NodeEvent.StateSnapshotCommit commit -> commit.sessionId();
      case NodeEvent.StateDiff diff -> diff.sessionId();
      case NodeEvent.DiffTruncated truncated -> truncated.sessionId();
      case NodeEvent.AgentNavigationFailed failed -> failed.sessionId();
      case NodeEvent.AgentActionFailed failed -> failed.sessionId();
      case NodeEvent.HumanAssistFailed failed -> failed.sessionId();
      case NodeEvent.RemoteDesktopParticipantChanged changed -> changed.sessionId();
      case NodeEvent.EvidenceCaptured captured -> captured.sessionId();
      case NodeEvent.HumanTakeoverReady ready -> ready.sessionId();
      case NodeEvent.HumanTakeoverEnded ended -> ended.sessionId();
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

    var session = sessionRepository.requireForUpdate(timeout.sessionId());
    if (session.state() == SessionState.STARTING
        || session.state() == SessionState.RECOVERING
        || session.state() == SessionState.HIBERNATING) {
      sessionRepository.updateWithExpectedEpoch(
          session.withState(SessionState.FAILED), session.contextEpoch());
      outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.FAILED));
    } else if (session.state() == SessionState.TERMINATING) {
      sessionRepository.updateWithExpectedEpoch(
          session.withState(SessionState.TERMINATED), session.contextEpoch());
      outboxPublisher.append(new SessionStateChanged(session.sessionId(), SessionState.TERMINATED));
    }

    // 发布事件
    outboxPublisher.append(new OperationTimedOutEvent(timeout.sessionId(), timeout.operationId()));

    return CoordinatorResult.completed();
  }
}
