package io.browsercloud.application;

import io.browsercloud.api.*;
import io.browsercloud.coordinator.*;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.time.Instant;
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
  private final IdempotencyService idempotencyService;
  private final String defaultRuntimeBuildId;

  public SessionApplicationService(
      SessionCoordinator coordinator,
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      IdempotencyService idempotencyService,
      @Value("${browser-node.default-runtime-build-id:runtime_local_chromium}")
          String defaultRuntimeBuildId) {
    this.coordinator = coordinator;
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.idempotencyService = idempotencyService;
    this.defaultRuntimeBuildId = defaultRuntimeBuildId;
  }

  /** 创建 Session。 */
  @Transactional
  public CreateSessionResponse create(CreateSessionRequest request, String idempotencyKey) {
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

    return new CreateSessionResponse(candidateSessionId, toContextView(context));
  }

  /** 启动 Session。 */
  @Transactional
  public OperationResponse start(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    var result =
        coordinator.handle(
            new StartSession(sessionId, defaultRuntimeBuildId, UUID.randomUUID().toString()));
    return new OperationResponse(
        result.operationId(), io.browsercloud.domain.operation.OperationState.ACTIVE);
  }

  /** 终止 Session。 */
  @Transactional
  public OperationResponse terminate(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    var result = coordinator.handle(new TerminateSession(sessionId, "user_request"));
    return new OperationResponse(
        result.operationId(), io.browsercloud.domain.operation.OperationState.ACTIVE);
  }

  /** 获取 Session。 */
  public SessionView get(String sessionId, String tenantId) {
    var context = requireTenant(sessionId, tenantId);
    return toView(context);
  }

  /** 列出 Sessions。 */
  public SessionListResponse list(String tenantId, SessionState state, int limit, int offset) {
    int safeLimit = Math.max(1, Math.min(limit, 100));
    int safeOffset = Math.max(0, offset);
    var contexts = sessionRepository.listByTenant(tenantId, state, safeLimit, safeOffset);
    var items = contexts.stream().map(this::toView).toList();
    long count = sessionRepository.countByTenant(tenantId, state);
    return new SessionListResponse(
        items, Math.toIntExact(Math.min(count, Integer.MAX_VALUE)), safeLimit, safeOffset);
  }

  private SessionView toView(SessionContext context) {
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
        context.tenantId(),
        context.state(),
        context.nodeId(),
        context.runtimeBuildId(),
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
    if (!context.tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(sessionId);
    }
    return context;
  }
}
