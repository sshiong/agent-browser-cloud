package io.browsercloud.api;

import static io.browsercloud.api.SessionResourceModels.*;
import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.application.CoordinatorCommandRoutingService;
import io.browsercloud.application.SafePointApplicationService;
import io.browsercloud.application.SessionApplicationService;
import io.browsercloud.application.SessionEvidenceApplicationService;
import io.browsercloud.application.SessionEvidenceGovernanceService;
import io.browsercloud.application.SessionMigrationApplicationService;
import io.browsercloud.application.SessionResourceApplicationService;
import io.browsercloud.application.SessionResourceEventStreamService;
import io.browsercloud.application.SessionSafetyLeaseApplicationService;
import io.browsercloud.application.StateGatewayApplicationService;
import io.browsercloud.coordinator.SessionListFilter;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Session REST API 控制器。
 *
 * <p>提供 Session 生命周期管理的 RESTful API。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@Validated
@PreAuthorize(PlatformRoles.READ)
public class SessionController {

  private final SessionApplicationService service;
  private final StateGatewayApplicationService stateGateway;
  private final PlatformIdentity identity;
  private final SessionResourceApplicationService resourceService;
  private final SafePointApplicationService safePointService;
  private final SessionSafetyLeaseApplicationService safetyLeaseService;
  private final SessionMigrationApplicationService migrationService;
  private final SessionResourceEventStreamService resourceEventStream;
  private final SessionEvidenceApplicationService evidenceService;
  private final SessionEvidenceGovernanceService evidenceGovernance;
  private final CoordinatorCommandRoutingService commandRouting;

  public SessionController(
      SessionApplicationService service,
      StateGatewayApplicationService stateGateway,
      PlatformIdentity identity,
      SessionResourceApplicationService resourceService,
      SafePointApplicationService safePointService,
      SessionSafetyLeaseApplicationService safetyLeaseService,
      SessionMigrationApplicationService migrationService,
      SessionResourceEventStreamService resourceEventStream,
      SessionEvidenceApplicationService evidenceService,
      SessionEvidenceGovernanceService evidenceGovernance,
      CoordinatorCommandRoutingService commandRouting) {
    this.service = service;
    this.stateGateway = stateGateway;
    this.identity = identity;
    this.resourceService = resourceService;
    this.safePointService = safePointService;
    this.safetyLeaseService = safetyLeaseService;
    this.migrationService = migrationService;
    this.resourceEventStream = resourceEventStream;
    this.evidenceService = evidenceService;
    this.evidenceGovernance = evidenceGovernance;
    this.commandRouting = commandRouting;
  }

  /**
   * 创建新 Session。
   *
   * @param request 创建请求
   * @param idempotencyKey 幂等键
   * @return 创建响应
   */
  @PostMapping
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<CreateSessionResponse> create(
      @Valid @RequestBody CreateSessionRequest request,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      HttpServletRequest servletRequest) {
    var principal = identity.current();
    if (!principal.tenantId().equals(request.tenantId())) {
      throw new TenantAccessDeniedException("new-session");
    }
    var result =
        service.create(
            request,
            idempotencyKey,
            principal.actorId(),
            String.valueOf(
                servletRequest.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE)),
            principal.roles().contains("PLATFORM_ADMIN"));
    return ResponseEntity.status(201).body(result);
  }

  /**
   * 启动 Session。
   *
   * @param sessionId Session ID
   * @return 操作响应
   */
  @PostMapping("/{sessionId}:start")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<OperationResponse> start(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            commandRouting.execute(
                sessionId,
                principal.tenantId(),
                SESSION_START,
                requestId(request),
                new SessionActor(principal.tenantId(), principal.actorId()),
                OperationResponse.class,
                () -> service.start(sessionId, principal.tenantId(), principal.actorId())));
  }

  /**
   * 终止 Session。
   *
   * @param sessionId Session ID
   * @return 操作响应
   */
  @PostMapping("/{sessionId}:terminate")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<OperationResponse> terminate(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            commandRouting.execute(
                sessionId,
                principal.tenantId(),
                SESSION_TERMINATE,
                requestId(request),
                new SessionActor(principal.tenantId(), principal.actorId()),
                OperationResponse.class,
                () -> service.terminate(sessionId, principal.tenantId(), principal.actorId())));
  }

  /** 获取排他人工接管权，并在 Browser Node 建立输入释放屏障。 */
  @PostMapping("/{sessionId}:takeover")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<OperationResponse> requestTakeover(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            commandRouting.execute(
                sessionId,
                principal.tenantId(),
                SESSION_TAKEOVER,
                requestId(request),
                new SessionActor(principal.tenantId(), principal.actorId()),
                OperationResponse.class,
                () ->
                    service.requestTakeover(sessionId, principal.tenantId(), principal.actorId())));
  }

  /** 释放人工接管权；完成 All-keys-up 和 State Resync 后 Operation 才会提交。 */
  @PostMapping("/{sessionId}:release-takeover")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<OperationResponse> releaseTakeover(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            commandRouting.execute(
                sessionId,
                principal.tenantId(),
                SESSION_RELEASE_TAKEOVER,
                requestId(request),
                new SessionActor(principal.tenantId(), principal.actorId()),
                OperationResponse.class,
                () ->
                    service.releaseTakeover(sessionId, principal.tenantId(), principal.actorId())));
  }

  /** 为运行中的 Session 签发协作 noVNC 票据；连接本身不抢占或停止 Agent。 */
  @PostMapping("/{sessionId}:desktop-connection")
  @PreAuthorize(PlatformRoles.OPERATE)
  public RemoteDesktopConnectionResponse createDesktopConnection(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(defaultValue = "false") boolean viewOnly) {
    var principal = identity.current();
    return service.createDesktopConnection(
        sessionId, principal.tenantId(), principal.actorId(), viewOnly);
  }

  /**
   * 获取 Session 详情。
   *
   * @param sessionId Session ID
   * @return Session 视图
   */
  @GetMapping("/{sessionId}")
  public SessionView get(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.get(sessionId, identity.current().tenantId());
  }

  /** 获取 Browser Node 最近提交的 Current State。 */
  @GetMapping("/{sessionId}/state")
  public ResponseEntity<BrowserStateView> getState(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service
        .getState(sessionId, identity.current().tenantId())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/{sessionId}/resources")
  public SessionResourceView getResources(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return resourceService.get(sessionId, identity.current().tenantId());
  }

  @GetMapping("/{sessionId}/resource-events")
  public ResourceEventListResponse getResourceEvents(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return resourceService.events(sessionId, identity.current().tenantId(), limit, offset);
  }

  @GetMapping("/{sessionId}/evidence")
  public SessionEvidenceModels.EvidenceListResponse getEvidence(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return evidenceService.list(sessionId, identity.current().tenantId(), limit, offset);
  }

  /** Requests a real Browser screenshot. Completion arrives through SessionEvidenceCaptured. */
  @PostMapping("/{sessionId}/evidence:capture")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ResponseEntity<SessionEvidenceModels.EvidenceCaptureView> captureEvidence(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody SessionEvidenceModels.CaptureEvidenceRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            evidenceGovernance.capture(
                sessionId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                requestId(request),
                body));
  }

  @GetMapping("/{sessionId}/evidence-captures/{captureId}")
  @PreAuthorize(PlatformRoles.ADMIN)
  public SessionEvidenceModels.EvidenceCaptureView getEvidenceCapture(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^cap_[a-zA-Z0-9]{16,}$") String captureId) {
    return evidenceGovernance.getCapture(sessionId, captureId, identity.current().tenantId());
  }

  /** Creates a purpose-bound five-minute grant without exposing raw storage coordinates. */
  @PostMapping("/{sessionId}/evidence/{evidenceId}/access-grants")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ResponseEntity<SessionEvidenceModels.EvidenceAccessGrantView> createEvidenceAccessGrant(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^evd_[a-zA-Z0-9]{16,}$") String evidenceId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody SessionEvidenceModels.CreateEvidenceAccessGrantRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    return ResponseEntity.status(201)
        .body(
            evidenceGovernance.createAccessGrant(
                sessionId,
                evidenceId,
                principal.tenantId(),
                principal.actorId(),
                idempotencyKey,
                requestId(request),
                body));
  }

  /** Redeems the grant exactly once and returns an ephemeral 60-second signed download URL. */
  @PostMapping("/{sessionId}/evidence-access-grants/{grantId}:redeem")
  @PreAuthorize(PlatformRoles.ADMIN)
  public SessionEvidenceModels.RedeemEvidenceAccessResponse redeemEvidenceAccessGrant(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^egr_[a-zA-Z0-9]{16,}$") String grantId,
      HttpServletRequest request) {
    var principal = identity.current();
    return evidenceGovernance.redeem(
        sessionId, grantId, principal.tenantId(), principal.actorId(), requestId(request));
  }

  @GetMapping(value = "/{sessionId}/resource-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamResourceChanges(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    return resourceEventStream.subscribe(sessionId, identity.current().tenantId(), lastEventId);
  }

  /** 获取统一、可续传的 Session 生命周期、状态、审计与资源变更流。 */
  @GetMapping(value = "/{sessionId}/event-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamSessionChanges(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    return resourceEventStream.subscribeSessionEvents(
        sessionId, identity.current().tenantId(), lastEventId);
  }

  @GetMapping("/{sessionId}/safe-point")
  public SafePointModels.SessionSafePointView getSafePoint(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return safePointService.assess(sessionId, identity.current().tenantId());
  }

  @PostMapping("/{sessionId}/safety-leases")
  @PreAuthorize(PlatformRoles.APPLICATION_SIGNAL)
  public ResponseEntity<SafePointModels.SafetyLeaseView> acquireSafetyLease(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody SafePointModels.CreateSafetyLeaseRequest request) {
    var principal = identity.current();
    return ResponseEntity.status(201)
        .body(
            safetyLeaseService.acquire(
                sessionId, principal.tenantId(), principal.actorId(), idempotencyKey, request));
  }

  @GetMapping("/{sessionId}/safety-leases")
  public SafePointModels.SafetyLeaseListResponse listSafetyLeases(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return safetyLeaseService.list(sessionId, identity.current().tenantId(), limit);
  }

  @PutMapping("/{sessionId}/safety-leases/{leaseId}")
  @PreAuthorize(PlatformRoles.APPLICATION_SIGNAL)
  public SafePointModels.SafetyLeaseView renewSafetyLease(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^sfl_[a-zA-Z0-9]{16,}$") String leaseId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody SafePointModels.RenewSafetyLeaseRequest request) {
    var principal = identity.current();
    return safetyLeaseService.renew(
        sessionId, leaseId, principal.tenantId(), principal.actorId(), idempotencyKey, request);
  }

  @PostMapping("/{sessionId}/safety-leases/{leaseId}:release")
  @PreAuthorize(PlatformRoles.APPLICATION_SIGNAL)
  public SafePointModels.SafetyLeaseView releaseSafetyLease(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @PathVariable @Pattern(regexp = "^sfl_[a-zA-Z0-9]{16,}$") String leaseId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
    var principal = identity.current();
    return safetyLeaseService.release(
        sessionId, leaseId, principal.tenantId(), principal.actorId(), idempotencyKey);
  }

  @GetMapping("/{sessionId}/migration")
  public ResponseEntity<SessionMigrationView> getLatestMigration(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return migrationService
        .latest(sessionId, identity.current().tenantId())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * Checkpoint, stop, switch the durable proxy assignment, restore, resync and validate business
   * recovery. The endpoint never claims an in-place or connection-transparent proxy change.
   */
  @PostMapping("/{sessionId}/proxy-binding:rebind")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ResponseEntity<ProxyBindingModels.ProxyRebindOperationResponse> rebindProxy(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody ProxyBindingModels.ProxyRebindRequest body,
      HttpServletRequest request) {
    var principal = identity.current();
    var requestId = requestId(request);
    return ResponseEntity.accepted()
        .body(
            commandRouting.execute(
                sessionId,
                principal.tenantId(),
                PROXY_REBIND_REQUEST,
                requestId,
                new ProxyRebindRequestCommand(
                    principal.tenantId(),
                    principal.actorId(),
                    body.targetBindingProfileId(),
                    body.reason(),
                    idempotencyKey,
                    requestId),
                ProxyBindingModels.ProxyRebindOperationResponse.class,
                () ->
                    migrationService.requestProxyRebind(
                        sessionId,
                        principal.tenantId(),
                        principal.actorId(),
                        body.targetBindingProfileId(),
                        body.reason(),
                        idempotencyKey,
                        requestId)));
  }

  @GetMapping("/{sessionId}/proxy-rebind")
  public ResponseEntity<ProxyBindingModels.ProxyRebindView> getLatestProxyRebind(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return migrationService
        .latestProxyRebind(sessionId, identity.current().tenantId())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PatchMapping("/{sessionId}/resource-policy")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<ResourcePolicyOperationResponse> updateResourcePolicy(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody ResourcePolicyRequest request) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(
            resourceService.update(
                sessionId,
                principal.tenantId(),
                request,
                idempotencyKey,
                principal.actorId(),
                principal.roles().contains("PLATFORM_ADMIN")));
  }

  @PostMapping("/{sessionId}/resource-samples")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public ResponseEntity<SessionResourceView> recordResourceSample(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @Valid @RequestBody RecordResourceSampleRequest request) {
    return ResponseEntity.accepted().body(resourceService.recordSample(sessionId, request));
  }

  /** 请求 Full 或 Region State Resync；结果由后续 BrowserStateUpdated 事件提交。 */
  @PostMapping("/{sessionId}:resync-state")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<StateResyncResponse> resyncState(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody StateResyncRequest request) {
    return ResponseEntity.accepted()
        .body(
            stateGateway.requestResync(
                sessionId, identity.current().tenantId(), request, idempotencyKey));
  }

  /**
   * 列出 Sessions。
   *
   * @param state 状态过滤
   * @param query 租户内名称、Session、Profile、区域或资源搜索
   * @param limit 每页数量
   * @param offset 偏移量
   * @return Session 列表
   */
  @GetMapping
  public SessionListResponse list(
      @RequestParam(required = false) SessionState state,
      @RequestParam(required = false, name = "q") @Size(max = 128) String query,
      @RequestParam(required = false) @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
      @RequestParam(required = false, name = "tagId")
          java.util.List<@Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds,
      @RequestParam(defaultValue = "ANY") SessionListFilter.TagMatch tagMatch,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    var normalizedTagIds =
        tagIds == null ? java.util.List.<String>of() : tagIds.stream().distinct().toList();
    if (normalizedTagIds.size() > 16) {
      throw new IllegalArgumentException("SESSION_TAG_FILTER_LIMIT_EXCEEDED");
    }
    return service.list(
        identity.current().tenantId(),
        state,
        query,
        new SessionListFilter(groupId, normalizedTagIds, tagMatch),
        limit,
        offset);
  }

  private static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE));
  }
}
