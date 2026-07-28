package io.browsercloud.application;

import io.browsercloud.api.*;
import io.browsercloud.coordinator.*;
import io.browsercloud.coordinator.exceptions.StaleOperationException;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.capacity.RuntimeResourceLimits;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.resource.ExecutionEnvironment;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session 应用服务。
 *
 * <p>负责协调 API 层和 Domain 层。
 */
@Service
public class SessionApplicationService {

  private final SessionCoordinator coordinator;
  private final SessionRepository sessionRepository;
  private final OperationRepository operationRepository;
  private final BrowserStateRepository browserStateRepository;
  private final IdempotencyService idempotencyService;
  private final RemoteDesktopTicketService remoteDesktopTicketService;
  private final ProfileApplicationService profileApplicationService;
  private final StaticProxyApplicationService proxyApplicationService;
  private final AuditApplicationService auditService;
  private final DurableWorkflowApplicationService workflowService;
  private final RuntimeBuildPolicy runtimeBuildPolicy;
  private final CapacityAdmissionService capacityAdmissionService;
  private final BrowserCapacityApplicationService browserCapacityService;
  private final SessionResourceApplicationService sessionResourceService;
  private final ApplicationBusinessRecoveryService businessRecoveryService;
  private final WorkspaceGroupApplicationService workspaceGroupService;
  private final WorkspaceTagApplicationService workspaceTagService;
  private final WorkspaceSettingsApplicationService workspaceSettingsService;
  private final TenantRouteApplicationService tenantRouteService;

  public SessionApplicationService(
      SessionCoordinator coordinator,
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      BrowserStateRepository browserStateRepository,
      IdempotencyService idempotencyService,
      RemoteDesktopTicketService remoteDesktopTicketService,
      ProfileApplicationService profileApplicationService,
      StaticProxyApplicationService proxyApplicationService,
      AuditApplicationService auditService,
      DurableWorkflowApplicationService workflowService,
      RuntimeBuildPolicy runtimeBuildPolicy,
      CapacityAdmissionService capacityAdmissionService,
      BrowserCapacityApplicationService browserCapacityService,
      SessionResourceApplicationService sessionResourceService,
      ApplicationBusinessRecoveryService businessRecoveryService,
      WorkspaceGroupApplicationService workspaceGroupService,
      WorkspaceTagApplicationService workspaceTagService,
      WorkspaceSettingsApplicationService workspaceSettingsService,
      TenantRouteApplicationService tenantRouteService) {
    this.coordinator = coordinator;
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.browserStateRepository = browserStateRepository;
    this.idempotencyService = idempotencyService;
    this.remoteDesktopTicketService = remoteDesktopTicketService;
    this.profileApplicationService = profileApplicationService;
    this.proxyApplicationService = proxyApplicationService;
    this.auditService = auditService;
    this.workflowService = workflowService;
    this.runtimeBuildPolicy = runtimeBuildPolicy;
    this.capacityAdmissionService = capacityAdmissionService;
    this.browserCapacityService = browserCapacityService;
    this.sessionResourceService = sessionResourceService;
    this.businessRecoveryService = businessRecoveryService;
    this.workspaceGroupService = workspaceGroupService;
    this.workspaceTagService = workspaceTagService;
    this.workspaceSettingsService = workspaceSettingsService;
    this.tenantRouteService = tenantRouteService;
  }

  /** 创建 Session。 */
  @Transactional
  public CreateSessionResponse create(
      CreateSessionRequest request,
      String idempotencyKey,
      String actorId,
      String requestId,
      boolean platformAdmin) {
    if (!capacityAdmissionService.snapshot().admissionOpen()) {
      throw new CapacityUnavailableException();
    }
    String candidateSessionId =
        "ses_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String claimedSessionId =
        idempotencyService.claimCreateSession(
            request.tenantId(), idempotencyKey, request, candidateSessionId);
    if (!claimedSessionId.equals(candidateSessionId)) {
      var existing = sessionRepository.require(claimedSessionId);
      var policy = sessionResourceService.get(existing.sessionId(), existing.tenantId()).policy();
      return new CreateSessionResponse(
          existing.sessionId(),
          sessionResourceService.creationOperationId(existing.sessionId(), existing.tenantId()),
          "CREATED",
          policy,
          toContextView(existing));
    }

    Instant now = Instant.now();
    profileApplicationService.ensureExists(request.tenantId(), request.profileId());
    workspaceGroupService.requireExists(request.tenantId(), request.groupId());
    var workspaceDefaults = workspaceSettingsService.resolve(request.tenantId());
    var runtimeBuildId =
        request.runtimeBuildId() == null
            ? workspaceDefaults.defaultRuntimeBuildId()
            : request.runtimeBuildId();
    runtimeBuildPolicy.requireApproved(runtimeBuildId);
    var humanTakeoverEnabled =
        request.humanTakeoverEnabled() == null
            ? workspaceDefaults.defaultHumanTakeoverEnabled()
            : request.humanTakeoverEnabled();
    var agentPolicy = request.agentPolicy() == null ? AgentPolicy.BALANCED : request.agentPolicy();
    var extensionIds = normalizeExtensionIds(request.extensionIds());
    var effectiveResourcePolicy =
        workspaceGroupService.resolvePolicy(
            request.tenantId(), request.groupId(), request.resourcePolicy());

    var context =
        new SessionContext(
            candidateSessionId,
            request.tenantId(),
            request.profileId(),
            null,
            runtimeBuildId,
            null,
            null,
            0,
            0,
            0,
            0,
            initialResourceClass(request, effectiveResourcePolicy),
            SessionState.CREATED,
            "",
            now,
            now);

    sessionRepository.insert(
        context,
        request.region() == null ? workspaceDefaults.defaultRegion() : request.region(),
        request.metadata() == null ? java.util.Map.of() : request.metadata(),
        request.groupId(),
        humanTakeoverEnabled,
        agentPolicy,
        extensionIds);
    tenantRouteService.bindNewSession(context.sessionId(), context.tenantId());
    workspaceTagService.assignInitial(
        context.tenantId(), actorId, context.sessionId(), request.tagIds(), requestId);
    businessRecoveryService.bind(
        context.sessionId(), context.tenantId(), request.applicationId(), now);
    browserCapacityService.recordDemand(
        context.sessionId(),
        context.tenantId(),
        context.resourceClass(),
        request.requestedTabs() == 0 ? 1 : request.requestedTabs(),
        request.agentActionsPerMinute(),
        request.remoteDesktop(),
        request.web3Workload(),
        request.mediaWorkload(),
        request.requestedMediaStreams(),
        request.mediaBitrateKbps(),
        extensionIds,
        now);
    var resourceOperation =
        sessionResourceService.initialize(
            context, effectiveResourcePolicy, actorId, idempotencyKey, platformAdmin);
    appendAudit(
        context,
        "SESSION_LIFECYCLE",
        actorId,
        "CREATE",
        "COMMITTED",
        Map.of(
            "profileId",
            request.profileId(),
            "runtimeBuildId",
            runtimeBuildId,
            "humanTakeoverEnabled",
            humanTakeoverEnabled,
            "agentPolicy",
            agentPolicy.name(),
            "extensionIds",
            extensionIds,
            "resourceClass",
            context.resourceClass().name()),
        idempotencyKey);

    return new CreateSessionResponse(
        candidateSessionId,
        resourceOperation.operationId(),
        "CREATED",
        resourceOperation.resourcePolicy(),
        toContextView(context));
  }

  private io.browsercloud.domain.session.ResourceClass initialResourceClass(
      CreateSessionRequest request, ResourcePolicyRequest effectiveResourcePolicy) {
    if (effectiveResourcePolicy != null) {
      if (effectiveResourcePolicy.executionEnvironment() == ExecutionEnvironment.NATIVE_OS) {
        return io.browsercloud.domain.session.ResourceClass.L5;
      }
      var minimum = effectiveResourcePolicy.minimumTemplate();
      if ("interactive-v1".equals(minimum)) return io.browsercloud.domain.session.ResourceClass.L3;
      if ("heavy-v1".equals(minimum)) return io.browsercloud.domain.session.ResourceClass.L4;
      if ("native-standard-v1".equals(minimum))
        return io.browsercloud.domain.session.ResourceClass.L5;
      return io.browsercloud.domain.session.ResourceClass.L2;
    }
    // Backward-compatible legacy clients; the Web console never submits a fixed class.
    return request.resourceClass() == null
        ? io.browsercloud.domain.session.ResourceClass.L2
        : request.resourceClass();
  }

  /** 启动 Session。 */
  @Transactional
  public OperationResponse start(String sessionId, String tenantId, String actorId) {
    var session = requireTenant(sessionId, tenantId);
    var runtimeBuildId =
        session.runtimeBuildId() == null || session.runtimeBuildId().isBlank()
            ? workspaceSettingsService.resolve(tenantId).defaultRuntimeBuildId()
            : session.runtimeBuildId();
    runtimeBuildPolicy.requireApproved(runtimeBuildId);
    var descriptor = sessionRepository.describe(sessionId);
    var placement = browserCapacityService.reserve(session, descriptor.region());
    session = sessionRepository.require(sessionId);
    proxyApplicationService.ensureBinding(session);
    var result =
        coordinator.handle(
            new StartSession(
                sessionId,
                runtimeBuildId,
                UUID.randomUUID().toString(),
                new RuntimeResourceLimits(
                    placement.effectiveResourceClass(),
                    placement.cpuMillis(),
                    placement.memoryRequestMib(),
                    placement.memoryLimitMib(),
                    placement.pidLimit(),
                    placement.tabBudget(),
                    placement.stateCollectorBudgetPercent(),
                    placement.remoteDesktopBitrateKbps(),
                    placement.extensionIds(),
                    placement.extensionCpuWeight(),
                    placement.mediaEncoderSlots(),
                    placement.backgroundTabsFrozen(),
                    placement.newTabsBlocked(),
                    placement.pausedExtensionIds(),
                    placement.successTraceSamplePercent(),
                    placement.requiresDesktop(),
                    placement.requiresGpu(),
                    placement.requiresNativeOs(),
                    placement.requiresIsolation()),
                profileApplicationService.get(tenantId, session.profileId()).latestCheckpointId()));
    var operation = operationRepository.findActive(sessionId).orElseThrow();
    boolean failoverCleanup = operation.mode() == OperationMode.TERMINATION;
    var workflowId =
        workflowService.start(
            tenantId,
            operation,
            failoverCleanup ? "TERMINATE_RUNTIME" : "START_RUNTIME",
            result.operationId(),
            "RELEASE_PROXY");
    operationRepository.attachWorkflow(result.operationId(), workflowId);
    appendAudit(
        session,
        "SESSION_OPERATION_TRANSITION",
        actorId,
        failoverCleanup ? "COORDINATOR_FAILOVER_ABORT" : "START_RUNTIME",
        "ACCEPTED",
        failoverCleanup
            ? Map.of(
                "operationId",
                result.operationId(),
                "abortedAction",
                "START_RUNTIME",
                "cleanup",
                "TERMINATE_RUNTIME")
            : Map.of(
                "operationId",
                result.operationId(),
                "runtimeBuildId",
                runtimeBuildId,
                "nodeId",
                placement.nodeId(),
                "effectiveResourceClass",
                placement.effectiveResourceClass().name()),
        result.operationId());
    return new OperationResponse(
        result.operationId(), io.browsercloud.domain.operation.OperationState.ACTIVE);
  }

  /** 终止 Session。 */
  @Transactional
  public OperationResponse terminate(String sessionId, String tenantId, String actorId) {
    return terminate(sessionId, tenantId, actorId, "user_request");
  }

  /** Node PSI 达到 Critical 后由有界压力治理调用；不向外暴露为用户 API。 */
  @Transactional
  public OperationResponse terminateForNodePressure(
      String sessionId, String tenantId, String nodeId) {
    return terminate(sessionId, tenantId, "system:node-pressure", "node_pressure:" + nodeId);
  }

  /** Strict-cost policy termination. The policy update itself requires Platform Admin. */
  @Transactional
  public OperationResponse terminateForResourcePolicy(String sessionId, String tenantId) {
    return terminate(
        sessionId, tenantId, "system:resource-policy", "resource_policy_strict_maximum_reached");
  }

  /** Resource-policy hibernation after the Safe Point Aggregator has returned SAFE. */
  @Transactional
  public OperationResponse hibernateForResourcePolicy(String sessionId, String tenantId) {
    var session = requireTenant(sessionId, tenantId);
    var result =
        coordinator.handle(new HibernateSession(sessionId, "resource_policy_maximum_reached"));
    var operation = operationRepository.findActive(sessionId).orElseThrow();
    var workflowId =
        workflowService.start(
            tenantId,
            operation,
            "HIBERNATE_RUNTIME",
            result.operationId(),
            "RESTORE_RUNNING_SESSION");
    operationRepository.attachWorkflow(result.operationId(), workflowId);
    appendAudit(
        session,
        "SESSION_OPERATION_TRANSITION",
        "system:resource-policy",
        "HIBERNATE_RUNTIME",
        "ACCEPTED",
        Map.of("operationId", result.operationId(), "reason", "maximum_reached"),
        result.operationId());
    return new OperationResponse(
        result.operationId(), io.browsercloud.domain.operation.OperationState.ACTIVE);
  }

  private OperationResponse terminate(
      String sessionId, String tenantId, String actorId, String reason) {
    var session = requireTenant(sessionId, tenantId);
    var result = coordinator.handle(new TerminateSession(sessionId, reason));
    var operation = operationRepository.findActive(sessionId).orElseThrow();
    var workflowId =
        workflowService.start(
            tenantId, operation, "TERMINATE_RUNTIME", result.operationId(), "RELEASE_PROXY");
    operationRepository.attachWorkflow(result.operationId(), workflowId);
    appendAudit(
        session,
        "SESSION_OPERATION_TRANSITION",
        actorId,
        "TERMINATE_RUNTIME",
        "ACCEPTED",
        Map.of("operationId", result.operationId(), "reason", reason),
        result.operationId());
    return new OperationResponse(
        result.operationId(), io.browsercloud.domain.operation.OperationState.ACTIVE);
  }

  /** 请求 HumanTakeover；Node 输入屏障完成后 Operation 进入 EXECUTING。 */
  @Transactional
  public OperationResponse requestTakeover(String sessionId, String tenantId, String userId) {
    requireTenant(sessionId, tenantId);
    if (!sessionRepository.describe(sessionId).humanTakeoverEnabled()) {
      throw new HumanTakeoverDisabledException();
    }
    var result = coordinator.handle(new RequestHumanTakeover(sessionId, userId));
    appendAudit(
        sessionRepository.require(sessionId),
        "HUMAN_GOVERNANCE",
        userId,
        "REQUEST_TAKEOVER",
        "ACCEPTED",
        Map.of("operationId", result.operationId()),
        result.operationId());
    return new OperationResponse(
        result.operationId(), io.browsercloud.domain.operation.OperationState.ACTIVE);
  }

  /** 结束 HumanTakeover；Node 释放全部输入并重采集状态后提交 Operation。 */
  @Transactional
  public OperationResponse releaseTakeover(String sessionId, String tenantId, String userId) {
    requireTenant(sessionId, tenantId);
    var result = coordinator.handle(new ReleaseHumanTakeover(sessionId, userId));
    appendAudit(
        sessionRepository.require(sessionId),
        "HUMAN_GOVERNANCE",
        userId,
        "RELEASE_TAKEOVER",
        "ACCEPTED",
        Map.of("operationId", result.operationId()),
        result.operationId());
    return new OperationResponse(
        result.operationId(), io.browsercloud.domain.operation.OperationState.ACTIVE);
  }

  /** 仅向正在执行接管、且 Actor 完全匹配的客户端签发一次性远程桌面票据。 */
  @Transactional(readOnly = true)
  public RemoteDesktopConnectionResponse createDesktopConnection(
      String sessionId, String tenantId, String userId) {
    var session = requireTenant(sessionId, tenantId);
    var operation =
        operationRepository
            .findActive(sessionId)
            .filter(active -> active.mode() == OperationMode.HUMAN_TAKEOVER)
            .filter(active -> active.phase() == OperationPhase.EXECUTING)
            .orElseThrow(
                () ->
                    new StaleOperationException(
                        sessionId, "EXECUTING_HUMAN_TAKEOVER", "NOT_FOUND"));
    if (!userId.equals(operation.actorId())) {
      throw new TenantAccessDeniedException(sessionId);
    }
    return remoteDesktopTicketService.issue(tenantId, sessionId, userId, operation);
  }

  /** 获取 Session。 */
  public SessionView get(String sessionId, String tenantId) {
    var descriptor = sessionRepository.describe(sessionId);
    requireTenant(descriptor.context(), tenantId);
    return toView(descriptor);
  }

  /** 列出 Sessions。 */
  public SessionListResponse list(
      String tenantId, SessionState state, String query, int limit, int offset) {
    int safeLimit = Math.max(1, Math.min(limit, 100));
    int safeOffset = Math.max(0, offset);
    var descriptors = sessionRepository.listByTenant(tenantId, state, query, safeLimit, safeOffset);
    var items = descriptors.stream().map(this::toView).toList();
    long count = sessionRepository.countByTenant(tenantId, state, query);
    return new SessionListResponse(
        items, Math.toIntExact(Math.min(count, Integer.MAX_VALUE)), safeLimit, safeOffset);
  }

  /** 获取最新 Browser Current State；尚未采集时返回空。 */
  public java.util.Optional<BrowserStateView> getState(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return browserStateRepository
        .find(sessionId)
        .filter(snapshot -> snapshot.tenantId().equals(tenantId))
        .map(
            snapshot -> {
              var state = snapshot.state();
              var targets =
                  state.targets().stream()
                      .map(
                          target ->
                              new BrowserStateView.InteractiveTargetView(
                                  target.targetRef(),
                                  target.role(),
                                  target.name(),
                                  target.bounds() == null
                                      ? null
                                      : new BrowserStateView.BoundsView(
                                          target.bounds().x(),
                                          target.bounds().y(),
                                          target.bounds().width(),
                                          target.bounds().height()),
                                  target.enabled(),
                                  target.visible(),
                                  target.sensitive()))
                      .toList();
              return new BrowserStateView(
                  state.sessionId(),
                  snapshot.contextEpoch(),
                  state.stateVersion(),
                  state.targetRevision(),
                  state.url(),
                  state.title(),
                  state.stateHash(),
                  state.stateQuality(),
                  targets);
            });
  }

  private SessionView toView(SessionDescriptor descriptor) {
    var context = descriptor.context();
    var operation =
        operationRepository
            .findActive(context.sessionId())
            .map(
                active ->
                    new OperationView(
                        active.operationId(),
                        active.ownerType(),
                        active.actorId(),
                        active.mode(),
                        active.priority(),
                        active.coordinatorTerm(),
                        active.contextEpoch(),
                        active.operationEpoch(),
                        active.workflowId(),
                        active.cancellable(),
                        active.preemptible(),
                        active.phase(),
                        active.state(),
                        active.allowedCapabilities(),
                        active.deadline()))
            .orElse(null);
    return new SessionView(
        context.sessionId(),
        descriptor.displayName(),
        context.tenantId(),
        context.profileId(),
        descriptor.groupId(),
        workspaceTagService.summariesForSession(context.tenantId(), context.sessionId()),
        descriptor.humanTakeoverEnabled(),
        descriptor.agentPolicy(),
        descriptor.extensionIds(),
        descriptor.region(),
        context.resourceClass(),
        context.state(),
        context.nodeId(),
        context.runtimeBuildId(),
        context.proxyBindingId(),
        context.contextEpoch(),
        context.browserGeneration(),
        operation,
        context.createdAt(),
        context.updatedAt());
  }

  private static java.util.List<String> normalizeExtensionIds(java.util.List<String> extensionIds) {
    if (extensionIds == null || extensionIds.isEmpty()) {
      return java.util.List.of();
    }
    return extensionIds.stream().map(String::strip).distinct().sorted().toList();
  }

  private SessionContextView toContextView(SessionContext context) {
    return new SessionContextView(
        context.sessionId(),
        context.tenantId(),
        context.profileId(),
        context.nodeId(),
        context.runtimeBuildId(),
        context.isolationProfileId(),
        context.proxyBindingId(),
        context.coordinatorTerm(),
        context.contextEpoch(),
        context.browserGeneration(),
        context.networkRevision(),
        context.resourceClass(),
        context.state(),
        context.policyHash(),
        context.createdAt(),
        context.updatedAt());
  }

  private SessionContext requireTenant(String sessionId, String tenantId) {
    var context = sessionRepository.require(sessionId);
    requireTenant(context, tenantId);
    return context;
  }

  private void requireTenant(SessionContext context, String tenantId) {
    if (!context.tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(context.sessionId());
    }
  }

  private void appendAudit(
      SessionContext session,
      String eventType,
      String actorId,
      String action,
      String result,
      Map<String, Object> details,
      String requestId) {
    auditService.append(
        new AuditApplicationService.AuditRecord(
            session.tenantId(),
            session.sessionId(),
            eventType,
            "USER",
            actorId,
            "SESSION",
            session.sessionId(),
            action,
            result,
            details,
            requestId));
  }

  public static final class CapacityUnavailableException extends RuntimeException {}

  public static final class HumanTakeoverDisabledException extends RuntimeException {}
}
