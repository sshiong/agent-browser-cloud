package io.browsercloud.api;

import static io.browsercloud.api.SessionResourceModels.*;

import io.browsercloud.application.SafePointApplicationService;
import io.browsercloud.application.SessionApplicationService;
import io.browsercloud.application.SessionMigrationApplicationService;
import io.browsercloud.application.SessionResourceApplicationService;
import io.browsercloud.application.StateGatewayApplicationService;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
  private final SessionMigrationApplicationService migrationService;

  public SessionController(
      SessionApplicationService service,
      StateGatewayApplicationService stateGateway,
      PlatformIdentity identity,
      SessionResourceApplicationService resourceService,
      SafePointApplicationService safePointService,
      SessionMigrationApplicationService migrationService) {
    this.service = service;
    this.stateGateway = stateGateway;
    this.identity = identity;
    this.resourceService = resourceService;
    this.safePointService = safePointService;
    this.migrationService = migrationService;
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
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
    var principal = identity.current();
    if (!principal.tenantId().equals(request.tenantId())) {
      throw new TenantAccessDeniedException("new-session");
    }
    var result = service.create(request, idempotencyKey, principal.actorId());
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
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(service.start(sessionId, principal.tenantId(), principal.actorId()));
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
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(service.terminate(sessionId, principal.tenantId(), principal.actorId()));
  }

  /** 获取排他人工接管权，并在 Browser Node 建立输入释放屏障。 */
  @PostMapping("/{sessionId}:takeover")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<OperationResponse> requestTakeover(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(service.requestTakeover(sessionId, principal.tenantId(), principal.actorId()));
  }

  /** 释放人工接管权；完成 All-keys-up 和 State Resync 后 Operation 才会提交。 */
  @PostMapping("/{sessionId}:release-takeover")
  @PreAuthorize(PlatformRoles.OPERATE)
  public ResponseEntity<OperationResponse> releaseTakeover(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    var principal = identity.current();
    return ResponseEntity.accepted()
        .body(service.releaseTakeover(sessionId, principal.tenantId(), principal.actorId()));
  }

  /** 为当前 HumanTakeover Actor 签发短期、单次使用的 noVNC 数据面票据。 */
  @PostMapping("/{sessionId}:desktop-connection")
  @PreAuthorize(PlatformRoles.OPERATE)
  public RemoteDesktopConnectionResponse createDesktopConnection(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    var principal = identity.current();
    return service.createDesktopConnection(sessionId, principal.tenantId(), principal.actorId());
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

  @GetMapping("/{sessionId}/safe-point")
  public SafePointModels.SessionSafePointView getSafePoint(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return safePointService.assess(sessionId, identity.current().tenantId());
  }

  @GetMapping("/{sessionId}/migration")
  public ResponseEntity<SessionMigrationView> getLatestMigration(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return migrationService
        .latest(sessionId, identity.current().tenantId())
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
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return service.list(identity.current().tenantId(), state, query, limit, offset);
  }
}
