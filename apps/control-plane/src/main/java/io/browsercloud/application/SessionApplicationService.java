package io.browsercloud.application;

import io.browsercloud.api.*;
import io.browsercloud.coordinator.*;
import io.browsercloud.coordinator.exceptions.StaleOperationException;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.capacity.RuntimeResourceLimits;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
  private final String defaultRuntimeBuildId;

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
      @Value("${browser-node.default-runtime-build-id:runtime_local_chromium}")
          String defaultRuntimeBuildId) {
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
    this.defaultRuntimeBuildId = defaultRuntimeBuildId;
  }

  /** 创建 Session。 */
  @Transactional
  public CreateSessionResponse create(
      CreateSessionRequest request, String idempotencyKey, String actorId) {
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
      return new CreateSessionResponse(existing.sessionId(), toContextView(existing));
    }

    Instant now = Instant.now();
    profileApplicationService.ensureExists(request.tenantId(), request.profileId());

    var context =
        new SessionContext(
            candidateSessionId,
            request.tenantId(),
            request.profileId(),
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            0,
            request.resourceClass() != null
                ? request.resourceClass()
                : io.browsercloud.domain.session.ResourceClass.L2,
            SessionState.CREATED,
            "",
            now,
            now);

    sessionRepository.insert(
        context,
        request.region() == null ? "local" : request.region(),
        request.metadata() == null ? java.util.Map.of() : request.metadata());
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
        request.extensionIds() == null ? java.util.List.of() : request.extensionIds(),
        now);
    appendAudit(
        context,
        "SESSION_LIFECYCLE",
        actorId,
        "CREATE",
        "COMMITTED",
        Map.of("profileId", request.profileId(), "resourceClass", context.resourceClass().name()),
        idempotencyKey);

    return new CreateSessionResponse(candidateSessionId, toContextView(context));
  }

  /** 启动 Session。 */
  @Transactional
  public OperationResponse start(String sessionId, String tenantId, String actorId) {
    var session = requireTenant(sessionId, tenantId);
    runtimeBuildPolicy.requireApproved(defaultRuntimeBuildId);
    var descriptor = sessionRepository.describe(sessionId);
    var placement = browserCapacityService.reserve(session, descriptor.region());
    session = sessionRepository.require(sessionId);
    proxyApplicationService.ensureBinding(session);
    var result =
        coordinator.handle(
            new StartSession(
                sessionId,
                defaultRuntimeBuildId,
                UUID.randomUUID().toString(),
                new RuntimeResourceLimits(
                    placement.effectiveResourceClass(),
                    placement.cpuMillis(),
                    placement.memoryRequestMib(),
                    placement.memoryLimitMib(),
                    placement.pidLimit(),
                    placement.tabBudget(),
                    placement.requiresDesktop(),
                    placement.requiresGpu(),
                    placement.requiresNativeOs(),
                    placement.requiresIsolation())));
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
                defaultRuntimeBuildId,
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
}
