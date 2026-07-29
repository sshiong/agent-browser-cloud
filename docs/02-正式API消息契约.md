# Agent Browser Cloud — 正式 API / 消息契约

> 版本：v1.0  
> 日期：2026-07-23  
> 状态：Contract Baseline（持续演进）  
> 基于：V16 最终架构设计 + 代码大骨架

> **Source of Truth**：`packages/contracts/proto/`、`packages/contracts/openapi/` 与
> `packages/contracts/json-schema/` 中的可执行文件具有最高优先级。本文代码块用于评审说明，
> CI 通过 Buf/Redocly/JSON 校验器检查真实契约；修改契约后必须运行 `make contracts` 与
> `make contracts-check`。

---

## 1. 契约管理原则

### 1.1 Schema Registry

所有契约由 Schema Registry 统一管理：

- Session Context Schema
- Exclusive Operation Schema
- State Cursor Schema
- Workflow Schema
- Profile Checkpoint Schema
- Runtime Manifest Schema
- API Request/Response Schema
- Event Envelope Schema
- Policy Schema
- Capability Snapshot Schema

### 1.2 版本标识

每个对象携带：

```
schema_name: session_context
schema_version: v1
minimum_reader_version: v1
minimum_writer_version: v1
compatibility_mode: FULL
migration_id: null
deprecated_after: null
removed_after: null
```

### 1.3 兼容模式

| 模式 | 说明 |
|------|------|
| BACKWARD | 新 Reader 可读取旧数据 |
| FORWARD | 旧 Reader 可忽略新字段 |
| FULL | 双向兼容 |
| NONE | 需要显式迁移和停机窗口 |

默认控制面事件采用 BACKWARD + FORWARD 兼容。

### 1.4 字段演进规则

**允许**：
- 新增 Optional Field
- 新增带默认值的字段
- 新增可忽略枚举值
- 扩展 metadata/policy_ref
- 增加新的 Capability

**禁止**：
- 修改字段语义
- 改变字段类型
- 复用已删除字段编号
- 将 Optional 改为 Required
- 改变 Epoch/ID 的语义
- 删除仍在兼容窗口内的字段

---

## 2. Protobuf 契约

### 2.1 Session Context

**文件**：`packages/contracts/proto/session/v1/session.proto`

```protobuf
syntax = "proto3";

package browsercloud.session.v1;

option java_package = "io.browsercloud.proto.session.v1";
option java_multiple_files = true;
option go_package = "browsercloud/proto/session/v1";

// Session 会话上下文
message SessionContext {
  string session_id = 1;
  string tenant_id = 2;
  string profile_id = 3;
  string node_id = 4;
  string runtime_build_id = 5;
  string proxy_binding_id = 6;
  string isolation_profile_id = 7;

  // 版本控制
  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 browser_generation = 12;
  int64 network_revision = 13;

  // 状态
  string resource_class = 20;
  SessionState state = 21;
  string policy_hash = 22;

  // 时间戳
  int64 created_at_ms = 30;
  int64 updated_at_ms = 31;
}

enum SessionState {
  SESSION_STATE_UNSPECIFIED = 0;
  SESSION_STATE_CREATED = 1;
  SESSION_STATE_STARTING = 2;
  SESSION_STATE_RUNNING = 3;
  SESSION_STATE_DEGRADED = 4;
  SESSION_STATE_HIBERNATING = 5;
  SESSION_STATE_HIBERNATED = 6;
  SESSION_STATE_RECOVERING = 7;
  SESSION_STATE_TERMINATING = 8;
  SESSION_STATE_TERMINATED = 9;
  SESSION_STATE_FAILED = 10;
}

// 创建 Session 请求
message CreateSessionRequest {
  string tenant_id = 1;
  string profile_id = 2;
  string region = 3;
  ResourcePolicy resource_policy = 4;
  bool video_recording = 5;
  map<string, string> metadata = 10;
}

// 创建 Session 响应
message CreateSessionResponse {
  string session_id = 1;
  SessionContext context = 2;
}
```

### 2.2 Exclusive Operation

```protobuf
// 排他操作
message ExclusiveOperation {
  string operation_id = 1;
  string session_id = 2;
  OwnerType owner_type = 3;
  OperationMode mode = 4;
  int32 priority = 5;
  string actor_id = 6;

  // 版本控制
  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 operation_epoch = 12;
  string workflow_id = 13;

  // 状态
  OperationPhase phase = 20;
  OperationState state = 21;
  int64 deadline_epoch_ms = 22;
  bool cancellable = 23;
  bool preemptible = 24;

  // 能力
  repeated string allowed_capabilities = 30;
}

enum OwnerType {
  OWNER_TYPE_UNSPECIFIED = 0;
  OWNER_TYPE_AGENT = 1;
  OWNER_TYPE_HUMAN = 2;
  OWNER_TYPE_SYSTEM = 3;
}

enum OperationMode {
  OPERATION_MODE_UNSPECIFIED = 0;
  OPERATION_MODE_AGENT_INTERACTIVE = 1;
  OPERATION_MODE_HUMAN_TAKEOVER = 2;
  OPERATION_MODE_HUMAN_ASSIST = 3;
  OPERATION_MODE_QUIESCE = 4;
  OPERATION_MODE_SNAPSHOT = 5;
  OPERATION_MODE_HIBERNATE = 6;
  OPERATION_MODE_RECOVERY = 7;
  OPERATION_MODE_PROXY_TRANSITION = 8;
  OPERATION_MODE_EXTENSION_MAINTENANCE = 9;
  OPERATION_MODE_TERMINATION = 10;
}

enum OperationPhase {
  OPERATION_PHASE_UNSPECIFIED = 0;
  OPERATION_PHASE_PREPARING = 1;
  OPERATION_PHASE_EXECUTING = 2;
  OPERATION_PHASE_FLUSHING = 3;
  OPERATION_PHASE_UPLOADING = 4;
  OPERATION_PHASE_VERIFYING = 5;
  OPERATION_PHASE_COMPLETING = 6;
}

enum OperationState {
  OPERATION_STATE_UNSPECIFIED = 0;
  OPERATION_STATE_ACTIVE = 1;
  OPERATION_STATE_COMMITTED = 2;
  OPERATION_STATE_ABORTED = 3;
  OPERATION_STATE_TIMED_OUT = 4;
}
```

### 2.3 State Cursor

```protobuf
// 状态指针
message StateCursor {
  string session_id = 1;
  int64 current_state_version = 2;
  string current_state_hash = 3;
  StateQuality state_quality = 4;

  // 版本控制
  int64 browser_generation = 10;
  int64 coordinator_term = 11;
  int64 context_epoch = 12;
  int64 target_revision = 13;
  int64 network_revision = 14;

  // 检查点
  string last_checkpoint_id = 20;
  int64 last_checkpoint_version = 21;
  int64 pending_event_count = 22;

  int64 updated_at_ms = 30;
}

enum StateQuality {
  STATE_QUALITY_UNSPECIFIED = 0;
  STATE_QUALITY_COMPLETE = 1;
  STATE_QUALITY_DEPTH_LIMITED = 2;
  STATE_QUALITY_RESYNCING = 3;
  STATE_QUALITY_DEGRADED = 4;
  STATE_QUALITY_INVALID = 5;
  STATE_QUALITY_VISION_REQUIRED = 6;
  STATE_QUALITY_HUMAN_REQUIRED = 7;
}
```

### 2.4 Node Command

**文件**：`packages/contracts/proto/node/v1/node_command.proto`

```protobuf
syntax = "proto3";

package browsercloud.node.v1;

option java_package = "io.browsercloud.proto.node.v1";
option java_multiple_files = true;

service NodeControlService {
  rpc Ping(PingRequest) returns (PingResponse);
  rpc Dispatch(DispatchRequest) returns (DispatchResponse);
}

message PingRequest {
  string caller_id = 1;
}

message PingResponse {
  string node_id = 1;
  string service_version = 2;
  int64 unix_time_ms = 3;
}

message CommandAck {
  string message_id = 1;
  bool accepted = 2;
  bool duplicate = 3;
  string error_code = 4;
  string error_message = 5;
}

message DispatchRequest {
  CommandEnvelope command = 1;
}

message DispatchResponse {
  CommandAck acknowledgement = 1;
}

service NodeEventService {
  rpc Publish(PublishRequest) returns (PublishResponse);
}

message PublishRequest {
  EventEnvelope event = 1;
}

message PublishResponse {
  string event_id = 1;
  bool accepted = 2;
  bool duplicate = 3;
  string error_code = 4;
  string error_message = 5;
}

// 命令信封
message CommandEnvelope {
  string message_id = 1;
  string command_type = 2;
  string tenant_id = 3;
  string session_id = 4;

  // 版本控制
  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 operation_epoch = 12;

  // 幂等
  string idempotency_key = 20;
  bytes payload = 21;
}

// 事件信封
message EventEnvelope {
  string event_id = 1;
  string event_type = 2;
  string tenant_id = 3;
  string session_id = 4;

  // 版本控制
  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 operation_epoch = 12;
  int64 sequence = 13;

  bytes payload = 20;
}

// 启动 Runtime 命令
message StartRuntimeCommand {
  string session_id = 1;
  string runtime_build_id = 2;
  string profile_id = 3;
  string display = 4;
  int32 cdp_port = 5;
}

// Runtime 启动事件
message RuntimeStartedEvent {
  string session_id = 1;
  uint32 pid = 2;
  uint64 browser_generation = 3;
  string cdp_endpoint = 4;
  string node_id = 5;
  string runtime_build_id = 6;
}

// 停止 Runtime 命令
message StopRuntimeCommand {
  string session_id = 1;
  string reason = 2;
}

// Runtime 停止事件
message RuntimeStoppedEvent {
  string session_id = 1;
  string reason = 2;
  int32 exit_code = 3;
}

// Browser Crash 事件
message BrowserCrashEvent {
  string session_id = 1;
  string crash_type = 2;
  string reason = 3;
  int64 detected_at_ms = 4;
}

// 释放所有输入命令
message ReleaseAllInputCommand {
  string session_id = 1;
  string reason = 2;
}
```

---

## 3. OpenAPI 契约

**文件**：`packages/contracts/openapi/session-api.yaml`

> 仓库中的 YAML 文件是可执行的唯一 OpenAPI Source of Truth；下方代码块用于说明主要端点。当前本地骨架使用 `X-Tenant-Id` 做租户防误读，生产环境必须从 Bearer 身份中派生 Tenant，不能信任客户端自报的 Header。

```yaml
openapi: 3.1.0
info:
  title: Agent Browser Cloud API
  description: |
    Agent Browser Cloud 控制面 API。
    提供 Session 生命周期管理、状态查询、操作控制等能力。
  version: 1.0.0
  contact:
    name: Agent Browser Cloud Team

servers:
  - url: http://localhost:8080
    description: 本地开发环境
  - url: https://api.browsercloud.io
    description: 生产环境

paths:
  /api/v1/sessions:
    post:
      operationId: createSession
      summary: 创建新 Session
      description: 创建一个新的浏览器会话
      tags: [Session]
      parameters:
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateSessionRequest'
      responses:
        '201':
          description: Session 创建成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CreateSessionResponse'
        '400':
          $ref: '#/components/responses/BadRequest'
        '409':
          $ref: '#/components/responses/Conflict'

    get:
      operationId: listSessions
      summary: 列出 Sessions
      description: 列出当前租户的所有 Sessions
      tags: [Session]
      parameters:
        - name: state
          in: query
          schema:
            $ref: '#/components/schemas/SessionState'
        - name: limit
          in: query
          schema:
            type: integer
            minimum: 1
            maximum: 100
            default: 20
        - name: offset
          in: query
          schema:
            type: integer
            minimum: 0
            default: 0
      responses:
        '200':
          description: Session 列表
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SessionListResponse'

  /api/v1/sessions/{sessionId}:
    get:
      operationId: getSession
      summary: 获取 Session 详情
      tags: [Session]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '200':
          description: Session 详情
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SessionView'
        '404':
          $ref: '#/components/responses/NotFound'

  /api/v1/sessions/{sessionId}:start:
    post:
      operationId: startSession
      summary: 启动 Session
      description: 启动指定 Session 的 Chromium Runtime
      tags: [Session]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '202':
          description: 启动请求已接受
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/OperationResponse'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/Conflict'

  /api/v1/sessions/{sessionId}:terminate:
    post:
      operationId: terminateSession
      summary: 终止 Session
      description: 终止指定 Session 并释放所有资源
      tags: [Session]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '202':
          description: 终止请求已接受
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/OperationResponse'
        '404':
          $ref: '#/components/responses/NotFound'

  /api/v1/sessions/{sessionId}/state:
    get:
      operationId: getSessionState
      summary: 获取 Session 当前状态
      tags: [State]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '200':
          description: Session 状态
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SessionStateView'

  /api/v1/sessions/{sessionId}/operation:
    get:
      operationId: getCurrentOperation
      summary: 获取当前操作
      tags: [Operation]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '200':
          description: 当前操作
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/OperationView'
        '204':
          description: 无活跃操作

components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT

  parameters:
    SessionId:
      name: sessionId
      in: path
      required: true
      schema:
        type: string
        pattern: '^ses_[a-zA-Z0-9]{16,}$'

    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema:
        type: string
        maxLength: 128

    TenantId:
      name: X-Tenant-Id
      in: header
      required: true
      schema:
        type: string
        minLength: 1
        maxLength: 128

  schemas:
    SessionState:
      type: string
      enum:
        - CREATED
        - STARTING
        - RUNNING
        - DEGRADED
        - HIBERNATING
        - HIBERNATED
        - RECOVERING
        - TERMINATING
        - TERMINATED
        - FAILED

    CreateSessionRequest:
      type: object
      required: [tenantId, profileId]
      properties:
        tenantId:
          type: string
          description: 租户 ID
        profileId:
          type: string
          description: Profile ID
        region:
          type: string
          description: 部署区域
        resourcePolicy:
          $ref: '#/components/schemas/ResourcePolicyRequest'
          description: 普通客户端提交 AUTO；内部 Resource Template 不作为用户等级暴露
        videoRecording:
          type: boolean
          default: false
          description: 启用独立 CDP Pixel Recording；由 Storage Helper 提交对象存储
        metadata:
          type: object
          additionalProperties:
            type: string

    CreateSessionResponse:
      type: object
      properties:
        sessionId:
          type: string
        context:
          $ref: '#/components/schemas/SessionContext'

    SessionContext:
      type: object
      properties:
        sessionId:
          type: string
        tenantId:
          type: string
        profileId:
          type: string
        nodeId:
          type: string
        runtimeBuildId:
          type: string
        proxyBindingId:
          type: string
        coordinatorTerm:
          type: integer
          format: int64
        contextEpoch:
          type: integer
          format: int64
        browserGeneration:
          type: integer
          format: int64
        networkRevision:
          type: integer
          format: int64
        resourceClass:
          type: string
        state:
          $ref: '#/components/schemas/SessionState'
        policyHash:
          type: string
        createdAt:
          type: string
          format: date-time
        updatedAt:
          type: string
          format: date-time

    SessionView:
      type: object
      properties:
        sessionId:
          type: string
        tenantId:
          type: string
        state:
          $ref: '#/components/schemas/SessionState'
        nodeId:
          type: string
        runtimeBuildId:
          type: string
        contextEpoch:
          type: integer
          format: int64
        browserGeneration:
          type: integer
          format: int64
        currentOperation:
          $ref: '#/components/schemas/OperationView'

    SessionListResponse:
      type: object
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/SessionView'
        total:
          type: integer
        limit:
          type: integer
        offset:
          type: integer

    OperationResponse:
      type: object
      properties:
        operationId:
          type: string
        state:
          type: string
          enum: [ACTIVE, COMMITTED, ABORTED, TIMED_OUT]

    OperationView:
      type: object
      properties:
        operationId:
          type: string
        ownerType:
          type: string
          enum: [AGENT, HUMAN, SYSTEM]
        mode:
          type: string
        priority:
          type: integer
        phase:
          type: string
        state:
          type: string
        deadline:
          type: string
          format: date-time

    SessionStateView:
      type: object
      properties:
        sessionId:
          type: string
        currentStateVersion:
          type: integer
          format: int64
        currentStateHash:
          type: string
        stateQuality:
          type: string
          enum:
            - COMPLETE
            - DEPTH_LIMITED
            - RESYNCING
            - DEGRADED
            - INVALID
            - VISION_REQUIRED
            - HUMAN_REQUIRED
        browserGeneration:
          type: integer
          format: int64
        contextEpoch:
          type: integer
          format: int64
        targetRevision:
          type: integer
          format: int64
        networkRevision:
          type: integer
          format: int64
        lastCheckpointId:
          type: string

    Error:
      type: object
      required: [code, message]
      properties:
        code:
          type: string
          description: 错误码
        message:
          type: string
          description: 错误信息
        details:
          type: object
          description: 错误详情
        requestId:
          type: string
          description: 请求 ID
        timestamp:
          type: string
          format: date-time

  responses:
    BadRequest:
      description: 请求参数错误
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'
    NotFound:
      description: 资源不存在
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'
    Conflict:
      description: 资源冲突
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'

security:
  - bearerAuth: []
```

---

## 4. 错误码规范

### 4.1 错误码格式

```
{DOMAIN}_{CATEGORY}_{DETAIL}
```

### 4.2 错误码列表

| 错误码 | HTTP 状态码 | 说明 |
|--------|------------|------|
| SESSION_NOT_FOUND | 404 | Session 不存在 |
| SESSION_ALREADY_EXISTS | 409 | Session 已存在 |
| SESSION_INVALID_STATE | 409 | Session 状态不允许此操作 |
| OPERATION_ACTIVE | 409 | 已有活跃操作 |
| OPERATION_NOT_FOUND | 404 | 操作不存在 |
| OPERATION_EXPIRED | 410 | 操作已过期 |
| COORDINATOR_TERM_MISMATCH | 409 | Coordinator 版本不匹配 |
| CONTEXT_EPOCH_MISMATCH | 409 | Context 版本不匹配 |
| NODE_UNAVAILABLE | 503 | Browser Node 不可用 |
| NODE_COMMAND_FAILED | 502 | Node 命令执行失败 |
| PROFILE_NOT_FOUND | 404 | Profile 不存在 |
| PROFILE_CORRUPTED | 500 | Profile 损坏 |
| PROXY_UNAVAILABLE | 503 | 代理不可用 |
| PROXY_BINDING_FAILED | 502 | 代理绑定失败 |
| STATE_INVALID | 409 | 状态无效 |
| STATE_RESYNC_REQUIRED | 409 | 需要状态重新同步 |
| HUMAN_TAKEOVER_REQUIRED | 409 | 需要人工接管 |
| PROMPT_INJECTION_DETECTED | 403 | 检测到 Prompt Injection |
| CAPABILITY_DENIED | 403 | 能力被拒绝 |
| RATE_LIMITED | 429 | 请求过于频繁 |
| INTERNAL_ERROR | 500 | 内部错误 |
| REQUEST_INVALID | 400 | 请求校验失败 |
| IDEMPOTENCY_KEY_REUSED | 409 | 幂等键被用于不同请求 |

---

## 5. 消息总线契约

### 5.1 Outbox Event 格式

```json
{
  "event_id": "evt_xxxxxxxxxxxxxxxx",
  "aggregate_type": "session",
  "aggregate_id": "ses_xxxxxxxxxxxxxxxx",
  "event_type": "session.context.committed",
  "schema_version": 1,
  "payload": {
    "session_id": "ses_xxxxxxxxxxxxxxxx",
    "context_epoch": 5,
    "coordinator_term": 2,
    "node_id": "node-001",
    "state": "RUNNING"
  },
  "created_at": "2026-07-23T10:00:00Z",
  "published_at": null
}
```

### 5.2 事件类型列表

| 事件类型 | 说明 | 发布方 |
|---------|------|--------|
| session.created | Session 创建 | Control Plane |
| session.context.committed | Context 提交 | Coordinator |
| session.state.changed | 状态变化 | Coordinator |
| operation.started | 操作开始 | Coordinator |
| operation.committed | 操作提交 | Coordinator |
| operation.aborted | 操作中止 | Coordinator |
| operation.timed_out | 操作超时 | Coordinator |
| workflow.dispatched | 工作流派发 | Coordinator |
| workflow.completed | 工作流完成 | Worker |
| workflow.failed | 工作流失败 | Worker |
| node.runtime.started | Runtime 启动 | Browser Node |
| node.runtime.stopped | Runtime 停止 | Browser Node |
| node.runtime.crashed | Runtime 崩溃 | Browser Node |
| profile.checkpoint.created | Profile 检查点创建 | Storage Helper |
| state.diff_truncated | 状态 Diff 截断 | State Collector |
| state.resync.completed | 状态重新同步完成 | State Collector |
| challenge.detected | Challenge 检测 | Challenge Service |
| human.authorization.created | 人工授权创建 | Human Service |

### 5.3 Inbox 去重

Consumer 端使用 Inbox 表去重：

```sql
CREATE TABLE inbox_events (
    event_id        TEXT PRIMARY KEY,
    consumer_id     TEXT NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 6. TypeScript 类型契约

### 6.1 Web Console 类型

```typescript
// packages/contracts/generated/typescript/session.ts

export interface SessionContext {
  sessionId: string;
  tenantId: string;
  profileId: string;
  nodeId?: string;
  runtimeBuildId?: string;
  proxyBindingId?: string;
  coordinatorTerm: number;
  contextEpoch: number;
  browserGeneration: number;
  networkRevision: number;
  resourceClass: ResourceClass;
  state: SessionState;
  policyHash: string;
  createdAt: string;
  updatedAt: string;
}

export type SessionState =
  | 'CREATED'
  | 'STARTING'
  | 'RUNNING'
  | 'DEGRADED'
  | 'HIBERNATING'
  | 'HIBERNATED'
  | 'RECOVERING'
  | 'TERMINATING'
  | 'TERMINATED'
  | 'FAILED';

export type ResourceClass = 'L0' | 'L1' | 'L2' | 'L3' | 'L4' | 'L5';

export interface SessionView {
  sessionId: string;
  tenantId: string;
  state: SessionState;
  nodeId?: string;
  runtimeBuildId?: string;
  contextEpoch: number;
  browserGeneration: number;
  currentOperation?: OperationView;
}

export interface OperationView {
  operationId: string;
  ownerType: 'AGENT' | 'HUMAN' | 'SYSTEM';
  mode: string;
  priority: number;
  phase: string;
  state: string;
  deadline: string;
}

export interface CreateSessionRequest {
  tenantId: string;
  profileId: string;
  region?: string;
  resourcePolicy?: ResourcePolicyRequest;
  videoRecording?: boolean;
  metadata?: Record<string, string>;
}

export interface CreateSessionResponse {
  sessionId: string;
  context: SessionContext;
}

export interface SessionStateView {
  sessionId: string;
  currentStateVersion: number;
  currentStateHash: string;
  stateQuality: StateQuality;
  browserGeneration: number;
  contextEpoch: number;
  targetRevision: number;
  networkRevision: number;
  lastCheckpointId?: string;
}

export type StateQuality =
  | 'COMPLETE'
  | 'DEPTH_LIMITED'
  | 'RESYNCING'
  | 'DEGRADED'
  | 'INVALID'
  | 'VISION_REQUIRED'
  | 'HUMAN_REQUIRED';

export interface ApiError {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  requestId?: string;
  timestamp?: string;
}
```

---

## 7. 契约兼容性检查

### 7.1 CI 检查项

- Protobuf 向后兼容性检查（buf breaking）
- OpenAPI 兼容性检查（oasdiff）
- JSON Schema 兼容性检查
- 生成代码更新检查

### 7.2 滚动升级支持

- Reader 必须容忍未知字段
- Writer 只写当前稳定版本
- 滚动升级期间支持 N 和 N-1
- 关键服务至少保留两个 Reader
- Major Schema 使用新 Topic 或新 API Version

### 7.3 Expand/Migrate/Contract 模式

数据库迁移顺序：

```
Expand
→ Deploy Compatible Readers
→ Deploy Compatible Writers
→ Backfill / Migrate
→ Observe
→ Contract
```

不允许先删字段再升级 Reader。

---

## 附录

### A. 文件结构

```
packages/contracts/
├── proto/
│   ├── session/v1/session.proto
│   ├── node/v1/node_command.proto
│   ├── workflow/v1/workflow.proto
│   └── runtime/v1/runtime.proto
├── openapi/
│   └── session-api.yaml
├── json-schema/
│   └── error-envelope.json
└── generated/
    ├── java/
    ├── rust/
    └── typescript/
```

### B. 代码生成命令

```bash
# Protobuf 生成
buf generate

# OpenAPI 生成
openapi-generator generate -i packages/contracts/openapi/session-api.yaml -g java -o packages/contracts/generated/java
openapi-generator generate -i packages/contracts/openapi/session-api.yaml -g typescript-fetch -o packages/contracts/generated/typescript
```
