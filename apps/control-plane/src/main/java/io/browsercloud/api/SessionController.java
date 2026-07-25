package io.browsercloud.api;

import io.browsercloud.application.SessionApplicationService;
import io.browsercloud.application.StateGatewayApplicationService;
import io.browsercloud.domain.session.SessionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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
public class SessionController {

  private final SessionApplicationService service;
  private final StateGatewayApplicationService stateGateway;

  public SessionController(
      SessionApplicationService service, StateGatewayApplicationService stateGateway) {
    this.service = service;
    this.stateGateway = stateGateway;
  }

  /**
   * 创建新 Session。
   *
   * @param request 创建请求
   * @param idempotencyKey 幂等键
   * @return 创建响应
   */
  @PostMapping
  public ResponseEntity<CreateSessionResponse> create(
      @Valid @RequestBody CreateSessionRequest request,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
    var result = service.create(request, idempotencyKey);
    return ResponseEntity.status(201).body(result);
  }

  /**
   * 启动 Session。
   *
   * @param sessionId Session ID
   * @return 操作响应
   */
  @PostMapping("/{sessionId}:start")
  public ResponseEntity<OperationResponse> start(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId) {
    return ResponseEntity.accepted().body(service.start(sessionId, tenantId));
  }

  /**
   * 终止 Session。
   *
   * @param sessionId Session ID
   * @return 操作响应
   */
  @PostMapping("/{sessionId}:terminate")
  public ResponseEntity<OperationResponse> terminate(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId) {
    return ResponseEntity.accepted().body(service.terminate(sessionId, tenantId));
  }

  /** 获取排他人工接管权，并在 Browser Node 建立输入释放屏障。 */
  @PostMapping("/{sessionId}:takeover")
  public ResponseEntity<OperationResponse> requestTakeover(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("X-Actor-Id") @NotBlank @Size(max = 128) String actorId) {
    return ResponseEntity.accepted().body(service.requestTakeover(sessionId, tenantId, actorId));
  }

  /** 释放人工接管权；完成 All-keys-up 和 State Resync 后 Operation 才会提交。 */
  @PostMapping("/{sessionId}:release-takeover")
  public ResponseEntity<OperationResponse> releaseTakeover(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("X-Actor-Id") @NotBlank @Size(max = 128) String actorId) {
    return ResponseEntity.accepted().body(service.releaseTakeover(sessionId, tenantId, actorId));
  }

  /** 为当前 HumanTakeover Actor 签发短期、单次使用的 noVNC 数据面票据。 */
  @PostMapping("/{sessionId}:desktop-connection")
  public RemoteDesktopConnectionResponse createDesktopConnection(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("X-Actor-Id") @NotBlank @Size(max = 128) String actorId) {
    return service.createDesktopConnection(sessionId, tenantId, actorId);
  }

  /**
   * 获取 Session 详情。
   *
   * @param sessionId Session ID
   * @return Session 视图
   */
  @GetMapping("/{sessionId}")
  public SessionView get(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId) {
    return service.get(sessionId, tenantId);
  }

  /** 获取 Browser Node 最近提交的 Current State。 */
  @GetMapping("/{sessionId}/state")
  public ResponseEntity<BrowserStateView> getState(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId) {
    return service
        .getState(sessionId, tenantId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /** 请求 Full 或 Region State Resync；结果由后续 BrowserStateUpdated 事件提交。 */
  @PostMapping("/{sessionId}:resync-state")
  public ResponseEntity<StateResyncResponse> resyncState(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId,
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
      @Valid @RequestBody StateResyncRequest request) {
    return ResponseEntity.accepted()
        .body(stateGateway.requestResync(sessionId, tenantId, request, idempotencyKey));
  }

  /**
   * 列出 Sessions。
   *
   * @param state 状态过滤
   * @param limit 每页数量
   * @param offset 偏移量
   * @return Session 列表
   */
  @GetMapping
  public SessionListResponse list(
      @RequestHeader("X-Tenant-Id") @NotBlank @Size(max = 128) String tenantId,
      @RequestParam(required = false) SessionState state,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return service.list(tenantId, state, limit, offset);
  }
}
