# Agent Browser Cloud 代码大骨架
## Java Control Plane + Rust Browser Node + React Console

> 本文档提供工程目录和关键代码接口的大骨架。
>
> 代码用于指导仓库初始化，不是完整实现。第一阶段应先完成单 Region、单 Runtime、基础 Profile、基础 Proxy 和 HumanTakeover。
>
> 默认技术选型：
>
> - Java 21：控制面、Session Coordinator、API、Workflow
> - Rust：Browser Node、Runtime Supervisor、Input、Network、Storage Helper
> - React + TypeScript：Console
> - PostgreSQL：权威状态
> - Redis：缓存、路由、短期状态
> - Protobuf：内部协议
> - OpenAPI：外部 API

---

# 1. Monorepo 目录

```text
agent-browser-cloud/
├── apps/
│   ├── control-plane/
│   │   ├── build.gradle.kts
│   │   ├── settings.gradle.kts
│   │   └── src/
│   ├── browser-node/
│   │   ├── Cargo.toml
│   │   └── crates/
│   ├── web-console/
│   │   ├── package.json
│   │   └── src/
│   └── cli/
│       ├── Cargo.toml
│       └── src/
├── packages/
│   ├── contracts/
│   │   ├── proto/
│   │   ├── openapi/
│   │   ├── json-schema/
│   │   └── generated/
│   ├── policy-schemas/
│   ├── sdk-typescript/
│   └── test-fixtures/
├── database/
│   ├── migrations/
│   └── seeds/
├── deploy/
│   ├── compose/
│   ├── kubernetes/
│   └── helm/
├── docs/
│   ├── adr/
│   ├── api/
│   ├── runbooks/
│   └── threat-model/
├── tests/
│   ├── integration/
│   ├── e2e/
│   ├── failure-injection/
│   └── replay-dataset/
├── tools/
│   ├── schema-check/
│   ├── local-dev/
│   └── benchmark/
├── Makefile
├── docker-compose.yml
└── README.md
```

---

# 2. Contract 骨架

## 2.1 Protobuf

`packages/contracts/proto/session/v1/session.proto`

```proto
syntax = "proto3";

package browsercloud.session.v1;

message SessionContext {
  string session_id = 1;
  string tenant_id = 2;
  string profile_id = 3;
  string node_id = 4;
  string runtime_build_id = 5;
  string proxy_binding_id = 6;

  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 browser_generation = 12;
  int64 network_revision = 13;

  string resource_class = 20;
  string state = 21;
  string policy_hash = 22;
}

message ExclusiveOperation {
  string operation_id = 1;
  string session_id = 2;
  string owner_type = 3;
  string mode = 4;
  int32 priority = 5;

  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 operation_epoch = 12;

  string phase = 20;
  string state = 21;
  int64 deadline_epoch_ms = 22;
}

message StateCursor {
  string session_id = 1;
  int64 current_state_version = 2;
  string current_state_hash = 3;
  string state_quality = 4;

  int64 browser_generation = 10;
  int64 context_epoch = 11;
  int64 target_revision = 12;
  int64 network_revision = 13;

  string last_checkpoint_id = 20;
  int64 checkpoint_epoch = 21;
}
```

## 2.2 Node Command

`packages/contracts/proto/node/v1/node_command.proto`

```proto
syntax = "proto3";

package browsercloud.node.v1;

message CommandEnvelope {
  string message_id = 1;
  string command_type = 2;
  string tenant_id = 3;
  string session_id = 4;

  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 operation_epoch = 12;

  string idempotency_key = 20;
  bytes payload = 21;
}

message EventEnvelope {
  string event_id = 1;
  string event_type = 2;
  string tenant_id = 3;
  string session_id = 4;

  int64 coordinator_term = 10;
  int64 context_epoch = 11;
  int64 operation_epoch = 12;
  int64 sequence = 13;

  bytes payload = 20;
}
```

## 2.3 OpenAPI

`packages/contracts/openapi/session-api.yaml`

```yaml
openapi: 3.1.0
info:
  title: Agent Browser Cloud API
  version: 1.0.0

paths:
  /api/v1/sessions:
    post:
      operationId: createSession
      responses:
        "201":
          description: Session created

  /api/v1/sessions/{sessionId}:start:
    post:
      operationId: startSession
      parameters:
        - in: path
          name: sessionId
          required: true
          schema:
            type: string
      responses:
        "202":
          description: Start accepted

  /api/v1/sessions/{sessionId}:terminate:
    post:
      operationId: terminateSession
      responses:
        "202":
          description: Terminate accepted
```

---

# 3. Java Control Plane

## 3.1 模块目录

```text
apps/control-plane/
├── api/
├── application/
├── domain/
├── infrastructure/
├── coordinator/
├── workflow/
├── state/
├── policy/
├── security/
├── persistence/
└── bootstrap/
```

推荐依赖方向：

```text
api
→ application
→ domain

infrastructure
→ domain

coordinator
→ domain

workflow
→ domain
```

Domain 不依赖 Spring。

---

# 4. Java 核心领域对象

## 4.1 SessionContext

```java
package io.browsercloud.domain.session;

import java.time.Instant;

public record SessionContext(
        String sessionId,
        String tenantId,
        String profileId,
        String nodeId,
        String runtimeBuildId,
        String proxyBindingId,
        long coordinatorTerm,
        long contextEpoch,
        long browserGeneration,
        long networkRevision,
        ResourceClass resourceClass,
        SessionState state,
        String policyHash,
        Instant updatedAt
) {
    public SessionContext nextContextEpoch(
            String newNodeId,
            String newRuntimeBuildId,
            long newBrowserGeneration
    ) {
        return new SessionContext(
                sessionId,
                tenantId,
                profileId,
                newNodeId,
                newRuntimeBuildId,
                proxyBindingId,
                coordinatorTerm,
                contextEpoch + 1,
                newBrowserGeneration,
                networkRevision,
                resourceClass,
                state,
                policyHash,
                Instant.now()
        );
    }
}
```

```java
public enum SessionState {
    CREATED,
    STARTING,
    RUNNING,
    DEGRADED,
    HIBERNATING,
    HIBERNATED,
    RECOVERING,
    TERMINATING,
    TERMINATED,
    FAILED
}
```

## 4.2 ExclusiveOperation

```java
package io.browsercloud.domain.operation;

import java.time.Instant;
import java.util.Set;

public record ExclusiveOperation(
        String operationId,
        String sessionId,
        OwnerType ownerType,
        OperationMode mode,
        int priority,
        long coordinatorTerm,
        long contextEpoch,
        long operationEpoch,
        OperationPhase phase,
        OperationState state,
        Set<String> allowedCapabilities,
        Instant deadline
) {
    public boolean isActive() {
        return state == OperationState.ACTIVE;
    }

    public boolean isExpired(Instant now) {
        return !deadline.isAfter(now);
    }
}
```

```java
public enum OwnerType {
    AGENT,
    HUMAN,
    SYSTEM
}
```

```java
public enum OperationMode {
    AGENT_INTERACTIVE,
    HUMAN_TAKEOVER,
    HUMAN_ASSIST,
    QUIESCE,
    SNAPSHOT,
    HIBERNATE,
    RECOVERY,
    PROXY_TRANSITION,
    EXTENSION_MAINTENANCE,
    TERMINATION
}
```

## 4.3 StateCursor

```java
package io.browsercloud.domain.state;

import java.time.Instant;

public record StateCursor(
        String sessionId,
        long currentStateVersion,
        String currentStateHash,
        StateQuality stateQuality,
        long browserGeneration,
        long contextEpoch,
        long targetRevision,
        long networkRevision,
        String lastCheckpointId,
        long checkpointEpoch,
        Instant updatedAt
) {
    public boolean canExecuteSemanticAction() {
        return stateQuality == StateQuality.COMPLETE
                || stateQuality == StateQuality.DEPTH_LIMITED;
    }
}
```

```java
public enum StateQuality {
    COMPLETE,
    DEPTH_LIMITED,
    RESYNCING,
    DEGRADED,
    INVALID,
    VISION_REQUIRED,
    HUMAN_REQUIRED
}
```

---

# 5. Session Coordinator 骨架

## 5.1 Command

```java
public sealed interface SessionCommand permits
        StartSession,
        TerminateSession,
        SubmitAgentAction,
        RequestHumanTakeover,
        NodeEventReceived,
        WorkflowCompleted,
        OperationTimedOut {

    String sessionId();
}
```

```java
public record StartSession(
        String sessionId,
        String requestedRuntimeBuildId,
        String idempotencyKey
) implements SessionCommand {}
```

```java
public record NodeEventReceived(
        String sessionId,
        NodeEvent event
) implements SessionCommand {}
```

## 5.2 Coordinator

```java
package io.browsercloud.coordinator;

public final class SessionCoordinator {

    private final SessionRepository sessionRepository;
    private final OperationRepository operationRepository;
    private final NodeCommandGateway nodeCommandGateway;
    private final OutboxPublisher outboxPublisher;

    public SessionCoordinator(
            SessionRepository sessionRepository,
            OperationRepository operationRepository,
            NodeCommandGateway nodeCommandGateway,
            OutboxPublisher outboxPublisher
    ) {
        this.sessionRepository = sessionRepository;
        this.operationRepository = operationRepository;
        this.nodeCommandGateway = nodeCommandGateway;
        this.outboxPublisher = outboxPublisher;
    }

    public CoordinatorResult handle(SessionCommand command) {
        return switch (command) {
            case StartSession start -> handleStart(start);
            case TerminateSession terminate -> handleTerminate(terminate);
            case NodeEventReceived event -> handleNodeEvent(event);
            case OperationTimedOut timeout -> handleTimeout(timeout);
            default -> CoordinatorResult.rejected("UNSUPPORTED_COMMAND");
        };
    }

    private CoordinatorResult handleStart(StartSession command) {
        var session = sessionRepository.require(command.sessionId());

        operationRepository.ensureNoActiveOperation(session.sessionId());

        var operation = OperationFactory.startRuntime(session);
        operationRepository.insert(operation);

        nodeCommandGateway.send(NodeCommands.startRuntime(session, operation));

        return CoordinatorResult.accepted(operation.operationId());
    }

    private CoordinatorResult handleTerminate(TerminateSession command) {
        // 校验优先级、创建 Termination Operation、发送节点命令。
        return CoordinatorResult.accepted("operation-id");
    }

    private CoordinatorResult handleNodeEvent(NodeEventReceived command) {
        // 校验 coordinator_term/context_epoch/operation_epoch。
        // 处理重复事件。
        // 提交 Operation 和 Session Context。
        return CoordinatorResult.completed();
    }

    private CoordinatorResult handleTimeout(OperationTimedOut timeout) {
        // Abort 当前 Operation，并触发补偿或 Recovery。
        return CoordinatorResult.completed();
    }
}
```

真实实现应通过事务保证：

- Operation 插入；
- Session Context 更新；
- Outbox Event 写入；

在同一个 PostgreSQL 事务完成。

---

# 6. Coordinator Mailbox

```java
public enum CommandLane {
    CRITICAL,
    INTERACTIVE,
    NORMAL,
    MAINTENANCE,
    TELEMETRY
}
```

```java
public interface SessionMailbox {
    void offer(CommandLane lane, SessionCommand command);

    SessionCommand poll();

    int size();

    long estimatedBytes();
}
```

MVP 可以使用有界 Priority Queue。

后续可替换为：

- Virtual Actor Runtime；
- Sharded Event Loop；
- Weighted Fair Queue；
- Shared Timer Wheel。

---

# 7. Durable Workflow 骨架

## 7.1 状态

```java
public enum WorkflowState {
    PENDING,
    DISPATCHED,
    RUNNING,
    COMPLETING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    ORPHANED,
    COMPENSATING,
    COMPENSATED,
    DEAD_LETTER
}
```

## 7.2 转换器

```java
public final class WorkflowStateMachine {

    private static final Map<WorkflowState, Set<WorkflowState>> ALLOWED = Map.of(
            WorkflowState.PENDING,
            Set.of(WorkflowState.DISPATCHED, WorkflowState.CANCELLED),

            WorkflowState.DISPATCHED,
            Set.of(
                    WorkflowState.RUNNING,
                    WorkflowState.CANCELLED,
                    WorkflowState.TIMED_OUT
            ),

            WorkflowState.RUNNING,
            Set.of(
                    WorkflowState.COMPLETING,
                    WorkflowState.FAILED,
                    WorkflowState.CANCELLED,
                    WorkflowState.TIMED_OUT
            ),

            WorkflowState.COMPLETING,
            Set.of(
                    WorkflowState.COMPLETED,
                    WorkflowState.FAILED,
                    WorkflowState.TIMED_OUT
            )
    );

    public void assertTransitionAllowed(
            WorkflowState from,
            WorkflowState to
    ) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(
                    "Illegal workflow transition: " + from + " -> " + to
            );
        }
    }
}
```

## 7.3 Workflow Record

```java
public record WorkflowExecution(
        String workflowId,
        String operationId,
        int attempt,
        String workflowType,
        WorkflowState state,
        String phase,
        String workerId,
        Instant heartbeatAt,
        Instant phaseDeadline,
        long cancellationEpoch,
        String idempotencyKey,
        String externalReceipt
) {}
```

---

# 8. Java Repository 接口

```java
public interface SessionRepository {
    SessionContext require(String sessionId);

    void insert(SessionContext context);

    void updateWithExpectedEpoch(
            SessionContext context,
            long expectedContextEpoch
    );
}
```

```java
public interface OperationRepository {
    void ensureNoActiveOperation(String sessionId);

    void insert(ExclusiveOperation operation);

    void transition(
            String operationId,
            OperationState expected,
            OperationState target
    );
}
```

```java
public interface StateCursorRepository {
    StateCursor get(String sessionId);

    void save(StateCursor cursor);
}
```

```java
public interface OutboxPublisher {
    void append(DomainEvent event);
}
```

---

# 9. Spring REST API 骨架

```java
@RestController
@RequestMapping("/api/v1/sessions")
public final class SessionController {

    private final SessionApplicationService service;

    public SessionController(SessionApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreateSessionResponse> create(
            @RequestBody CreateSessionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        var result = service.create(request, idempotencyKey);
        return ResponseEntity.status(201).body(result);
    }

    @PostMapping("/{sessionId}:start")
    public ResponseEntity<OperationResponse> start(
            @PathVariable String sessionId
    ) {
        return ResponseEntity.accepted()
                .body(service.start(sessionId));
    }

    @PostMapping("/{sessionId}:terminate")
    public ResponseEntity<OperationResponse> terminate(
            @PathVariable String sessionId
    ) {
        return ResponseEntity.accepted()
                .body(service.terminate(sessionId));
    }

    @GetMapping("/{sessionId}")
    public SessionView get(@PathVariable String sessionId) {
        return service.get(sessionId);
    }
}
```

---

# 10. PostgreSQL Migration 骨架

`database/migrations/V001__initial_control_plane.sql`

```sql
CREATE TABLE sessions (
    id                  TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    profile_id          TEXT NOT NULL,
    region              TEXT NOT NULL,
    state               TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    terminated_at       TIMESTAMPTZ
);

CREATE TABLE session_contexts (
    session_id          TEXT NOT NULL REFERENCES sessions(id),
    context_epoch       BIGINT NOT NULL,
    coordinator_term    BIGINT NOT NULL,
    node_id             TEXT,
    runtime_build_id    TEXT,
    proxy_binding_id    TEXT,
    network_revision    BIGINT NOT NULL DEFAULT 0,
    browser_generation  BIGINT NOT NULL DEFAULT 0,
    resource_class      TEXT NOT NULL,
    policy_hash         TEXT NOT NULL,
    committed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, context_epoch)
);

CREATE TABLE exclusive_operations (
    operation_id        TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL REFERENCES sessions(id),
    owner_type          TEXT NOT NULL,
    mode                TEXT NOT NULL,
    priority            INTEGER NOT NULL,
    operation_epoch     BIGINT NOT NULL,
    coordinator_term    BIGINT NOT NULL,
    context_epoch       BIGINT NOT NULL,
    phase               TEXT NOT NULL,
    state               TEXT NOT NULL,
    deadline            TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_active_operation_per_session
ON exclusive_operations(session_id)
WHERE state = 'ACTIVE';

CREATE TABLE outbox_events (
    event_id            TEXT PRIMARY KEY,
    aggregate_type      TEXT NOT NULL,
    aggregate_id        TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    schema_version      INTEGER NOT NULL,
    payload             JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ
);
```

---

# 11. Rust Browser Node

## 11.1 Workspace

```text
apps/browser-node/
├── Cargo.toml
└── crates/
    ├── node-agent/
    ├── runtime-supervisor/
    ├── browser-supervisor/
    ├── input-sandbox/
    ├── network-helper/
    ├── storage-helper/
    ├── state-collector/
    ├── node-journal/
    └── node-contracts/
```

`Cargo.toml`

```toml
[workspace]
members = [
  "crates/node-agent",
  "crates/runtime-supervisor",
  "crates/browser-supervisor",
  "crates/input-sandbox",
  "crates/network-helper",
  "crates/storage-helper",
  "crates/state-collector",
  "crates/node-journal",
  "crates/node-contracts"
]
resolver = "2"
```

---

# 12. Rust Runtime Supervisor

```rust
use async_trait::async_trait;
use std::path::PathBuf;

#[derive(Debug, Clone)]
pub struct RuntimeSpec {
    pub session_id: String,
    pub runtime_build_id: String,
    pub profile_dir: PathBuf,
    pub display: String,
    pub cdp_port: u16,
}

#[derive(Debug, Clone)]
pub struct RuntimeHandle {
    pub session_id: String,
    pub pid: u32,
    pub browser_generation: u64,
    pub cdp_endpoint: String,
}

#[async_trait]
pub trait RuntimeSupervisor: Send + Sync {
    async fn start(
        &self,
        spec: RuntimeSpec,
    ) -> anyhow::Result<RuntimeHandle>;

    async fn stop(
        &self,
        session_id: &str,
    ) -> anyhow::Result<()>;

    async fn health(
        &self,
        session_id: &str,
    ) -> anyhow::Result<RuntimeHealth>;
}

#[derive(Debug, Clone)]
pub enum RuntimeHealth {
    Healthy,
    Degraded(String),
    Crashed(String),
}
```

简单实现骨架：

```rust
pub struct ChromiumRuntimeSupervisor {
    chromium_binary: PathBuf,
}

#[async_trait]
impl RuntimeSupervisor for ChromiumRuntimeSupervisor {
    async fn start(
        &self,
        spec: RuntimeSpec,
    ) -> anyhow::Result<RuntimeHandle> {
        let child = tokio::process::Command::new(&self.chromium_binary)
            .arg(format!("--user-data-dir={}", spec.profile_dir.display()))
            .arg(format!("--remote-debugging-port={}", spec.cdp_port))
            .arg("--no-first-run")
            .arg("--disable-background-networking")
            .spawn()?;

        let pid = child.id()
            .ok_or_else(|| anyhow::anyhow!("missing Chromium pid"))?;

        Ok(RuntimeHandle {
            session_id: spec.session_id,
            pid,
            browser_generation: 1,
            cdp_endpoint: format!("http://127.0.0.1:{}", spec.cdp_port),
        })
    }

    async fn stop(&self, _session_id: &str) -> anyhow::Result<()> {
        Ok(())
    }

    async fn health(
        &self,
        _session_id: &str,
    ) -> anyhow::Result<RuntimeHealth> {
        Ok(RuntimeHealth::Healthy)
    }
}
```

生产实现不能简单使用固定端口，也不能直接信任 Session 参数。

---

# 13. Rust Node Agent

```rust
#[derive(Debug)]
pub struct NodeCommand {
    pub message_id: String,
    pub command_type: String,
    pub session_id: String,
    pub coordinator_term: u64,
    pub context_epoch: u64,
    pub operation_epoch: u64,
    pub idempotency_key: String,
    pub payload: Vec<u8>,
}
```

```rust
pub struct NodeAgent<R, J>
where
    R: RuntimeSupervisor,
    J: NodeJournal,
{
    runtime_supervisor: R,
    journal: J,
}

impl<R, J> NodeAgent<R, J>
where
    R: RuntimeSupervisor,
    J: NodeJournal,
{
    pub async fn handle(
        &self,
        command: NodeCommand,
    ) -> anyhow::Result<NodeEvent> {
        if self.journal.was_processed(&command.message_id).await? {
            return self.journal.previous_result(&command.message_id).await;
        }

        self.journal.validate_term(
            &command.session_id,
            command.coordinator_term,
        ).await?;

        let result = match command.command_type.as_str() {
            "StartRuntime" => self.handle_start(command).await?,
            "StopRuntime" => self.handle_stop(command).await?,
            "ReleaseAllInput" => self.handle_release_all(command).await?,
            _ => anyhow::bail!("unsupported command"),
        };

        self.journal.record_result(&result).await?;
        Ok(result)
    }
}
```

---

# 14. Node Journal

```rust
#[async_trait]
pub trait NodeJournal: Send + Sync {
    async fn was_processed(&self, message_id: &str)
        -> anyhow::Result<bool>;

    async fn previous_result(&self, message_id: &str)
        -> anyhow::Result<NodeEvent>;

    async fn validate_term(
        &self,
        session_id: &str,
        coordinator_term: u64,
    ) -> anyhow::Result<()>;

    async fn record_result(
        &self,
        event: &NodeEvent,
    ) -> anyhow::Result<()>;
}
```

MVP 可使用 SQLite 或嵌入式 KV，但必须：

- 有界；
- 可压缩；
- fsync 策略明确；
- 不保存完整 DOM；
- 不保存明文 Secret。

---

# 15. Input Sandbox

```rust
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum InputKey {
    Shift,
    Control,
    Alt,
    Meta,
    Character(String),
}

#[derive(Debug, Default)]
pub struct InputLedger {
    pub pressed_keys: std::collections::HashSet<InputKey>,
    pub pressed_buttons: std::collections::HashSet<u8>,
    pub active_drag: bool,
    pub last_sequence: u64,
}
```

```rust
#[async_trait]
pub trait DesktopInput: Send + Sync {
    async fn mouse_move(
        &self,
        x: i32,
        y: i32,
        sequence: u64,
    ) -> anyhow::Result<()>;

    async fn mouse_down(
        &self,
        button: u8,
        sequence: u64,
    ) -> anyhow::Result<()>;

    async fn mouse_up(
        &self,
        button: u8,
        sequence: u64,
    ) -> anyhow::Result<()>;

    async fn key_down(
        &self,
        key: InputKey,
        sequence: u64,
    ) -> anyhow::Result<()>;

    async fn key_up(
        &self,
        key: InputKey,
        sequence: u64,
    ) -> anyhow::Result<()>;

    async fn release_all(&self) -> anyhow::Result<()>;
}
```

Watchdog：

```rust
pub async fn run_release_watchdog<I: DesktopInput>(
    input: &I,
    ledger: &InputLedger,
    heartbeat_age: std::time::Duration,
) -> anyhow::Result<()> {
    if heartbeat_age > std::time::Duration::from_secs(5)
        && (!ledger.pressed_keys.is_empty()
            || !ledger.pressed_buttons.is_empty()
            || ledger.active_drag)
    {
        input.release_all().await?;
    }

    Ok(())
}
```

---

# 16. State Collector 骨架

```rust
#[derive(Debug, Clone)]
pub struct InteractiveTarget {
    pub target_ref: String,
    pub role: String,
    pub name: Option<String>,
    pub bounds: Option<Bounds>,
    pub enabled: bool,
    pub visible: bool,
}

#[derive(Debug, Clone)]
pub struct CurrentState {
    pub session_id: String,
    pub state_version: u64,
    pub target_revision: u64,
    pub url: String,
    pub title: String,
    pub targets: Vec<InteractiveTarget>,
    pub quality: StateQuality,
    pub content_hash: String,
}
```

```rust
#[async_trait]
pub trait BrowserStateCollector: Send + Sync {
    async fn collect_current_state(
        &self,
        session_id: &str,
    ) -> anyhow::Result<CurrentState>;

    async fn resync_region(
        &self,
        session_id: &str,
        root_ref: &str,
    ) -> anyhow::Result<CurrentState>;
}
```

MVP 可以先基于 CDP：

- DOMSnapshot
- Accessibility
- Runtime
- Page
- Target

后续再补：

- OOPIF；
- Shadow DOM；
- BFCache；
- CSP Fallback；
- Sensitive Classification。

---

# 17. Network Helper

```rust
#[derive(Debug, Clone)]
pub struct ProxyBindingSpec {
    pub binding_id: String,
    pub session_id: String,
    pub protocol: ProxyProtocol,
    pub host: String,
    pub port: u16,
    pub credential_ref: String,
}

#[derive(Debug, Clone)]
pub enum ProxyProtocol {
    Http,
    HttpsConnect,
    Socks5,
}
```

```rust
#[async_trait]
pub trait NetworkHelper: Send + Sync {
    async fn bind_proxy(
        &self,
        spec: ProxyBindingSpec,
    ) -> anyhow::Result<ObservedNetwork>;

    async fn verify_exit(
        &self,
        session_id: &str,
    ) -> anyhow::Result<ObservedNetwork>;

    async fn release(
        &self,
        session_id: &str,
    ) -> anyhow::Result<()>;
}
```

`credential_ref` 由 Network Helper 向 Vault 获取短期凭据，不能传给 Chromium 或 Agent。

---

# 18. Profile Storage

## 18.1 目录

```text
profiles/
└── {tenant_id}/
    └── {profile_id}/
        ├── core/
        ├── ephemeral/
        ├── checkpoints/
        └── metadata.json
```

## 18.2 Manifest

```rust
#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct ProfileCheckpointManifest {
    pub checkpoint_id: String,
    pub checkpoint_epoch: u64,
    pub profile_id: String,
    pub runtime_build_id: String,
    pub profile_write_epoch: u64,
    pub files: Vec<CheckpointFile>,
    pub content_hash: String,
    pub committed: bool,
}

#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct CheckpointFile {
    pub relative_path: String,
    pub size: u64,
    pub sha256: String,
}
```

MVP：

1. Quiesce；
2. Flush；
3. 复制 Core；
4. 生成 Manifest；
5. 写 Commit Marker；
6. 恢复校验。

后续再实现 SQLite/LevelDB Application-aware Adapter。

---

# 19. Agent Tool API

## 19.1 Tool 接口

```java
public interface AgentTool<I, O> {
    String toolId();

    ToolRisk risk();

    O execute(
            ToolExecutionContext context,
            I input
    );
}
```

```java
public record ToolExecutionContext(
        String tenantId,
        String sessionId,
        String intentId,
        String operationId,
        CapabilityToken capabilityToken,
        StateCursor stateCursor
) {}
```

## 19.2 Capability Token

```java
public record CapabilityToken(
        String tokenId,
        String tenantId,
        String sessionId,
        String intentId,
        String toolId,
        Set<String> allowedActions,
        Set<String> allowedDomains,
        Instant expiresAt,
        int maxCalls
) {}
```

Tool Service 必须校验 Token，不能只相信 Agent 文本。

---

# 20. Prompt Injection 骨架

```java
public enum InstructionSourceType {
    SYSTEM,
    PLATFORM_POLICY,
    TENANT_POLICY,
    USER_AUTHORIZATION,
    USER_REQUEST,
    APPLICATION_DATA,
    EMAIL,
    DOCUMENT,
    WEB_CONTENT,
    THIRD_PARTY_WIDGET
}
```

```java
public record TrustedContent(
        String contentId,
        InstructionSourceType sourceType,
        TrustLevel trustLevel,
        DataClassification classification,
        Set<String> taintLabels,
        String content
) {}
```

```java
public interface InstructionFirewall {
    SecurityDecision evaluate(
            AgentPlan plan,
            List<TrustedContent> supportingContent
    );
}
```

简单规则：

```java
public final class DefaultInstructionFirewall
        implements InstructionFirewall {

    @Override
    public SecurityDecision evaluate(
            AgentPlan plan,
            List<TrustedContent> content
    ) {
        boolean highRiskFromUntrusted = plan.steps().stream()
                .filter(step -> step.risk().isHigh())
                .anyMatch(step -> step.supportingSourceTypes().stream()
                        .allMatch(this::isUntrusted));

        if (highRiskFromUntrusted) {
            return SecurityDecision.block(
                    "HIGH_RISK_ACTION_FROM_UNTRUSTED_CONTENT"
            );
        }

        return SecurityDecision.allow();
    }

    private boolean isUntrusted(
            InstructionSourceType source
    ) {
        return source == InstructionSourceType.WEB_CONTENT
                || source == InstructionSourceType.EMAIL
                || source == InstructionSourceType.DOCUMENT
                || source == InstructionSourceType.THIRD_PARTY_WIDGET;
    }
}
```

---

# 21. Execution Strategy Selector

```java
public enum ExecutionStrategy {
    SEMANTIC_DOM,
    ACCESSIBILITY,
    DESKTOP_INPUT,
    VISION_DESKTOP,
    HUMAN_ASSIST,
    HUMAN_TAKEOVER
}
```

```java
public interface ExecutionStrategySelector {
    StrategyDecision select(
            PlannedAction action,
            StateCursor state,
            RuntimeCapabilities capabilities,
            RiskPolicy policy
    );
}
```

基础规则：

```java
public final class RuleBasedStrategySelector
        implements ExecutionStrategySelector {

    @Override
    public StrategyDecision select(
            PlannedAction action,
            StateCursor state,
            RuntimeCapabilities capabilities,
            RiskPolicy policy
    ) {
        if (state.stateQuality() == StateQuality.INVALID) {
            return StrategyDecision.humanTakeover(
                    "STATE_INVALID"
            );
        }

        if (action.target().isCanvas()) {
            return StrategyDecision.use(
                    ExecutionStrategy.DESKTOP_INPUT
            );
        }

        if (action.target().hasStableDomRef()) {
            return StrategyDecision.use(
                    ExecutionStrategy.SEMANTIC_DOM
            );
        }

        return StrategyDecision.use(
                ExecutionStrategy.ACCESSIBILITY
        );
    }
}
```

首版优先规则化，不急着用模型做策略选择。

---

# 22. React Console

## 22.1 目录

```text
apps/web-console/src/
├── app/
├── api/
├── auth/
├── components/
├── features/
│   ├── sessions/
│   ├── desktop/
│   ├── timeline/
│   ├── runtime/
│   ├── profiles/
│   └── security/
├── pages/
└── types/
```

## 22.2 Session API

```typescript
export interface SessionView {
  sessionId: string;
  tenantId: string;
  state: string;
  nodeId?: string;
  runtimeBuildId?: string;
  contextEpoch: number;
  browserGeneration: number;
  currentOperation?: OperationView;
}

export async function getSession(
  sessionId: string,
): Promise<SessionView> {
  const response = await fetch(`/api/v1/sessions/${sessionId}`);

  if (!response.ok) {
    throw new Error(`Failed to load session: ${response.status}`);
  }

  return response.json();
}
```

## 22.3 Session 页面

```tsx
export function SessionPage() {
  const { sessionId = "" } = useParams();
  const { data, error, isLoading } = useSession(sessionId);

  if (isLoading) {
    return <div>Loading session…</div>;
  }

  if (error || !data) {
    return <div>Unable to load session.</div>;
  }

  return (
    <main>
      <h1>{data.sessionId}</h1>
      <SessionStatusCard session={data} />
      <CurrentOperationCard operation={data.currentOperation} />
      <DesktopPanel sessionId={data.sessionId} />
      <TimelinePanel sessionId={data.sessionId} />
    </main>
  );
}
```

---

# 23. Docker Compose 骨架

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: browsercloud
      POSTGRES_USER: browsercloud
      POSTGRES_PASSWORD: browsercloud
    ports:
      - "5432:5432"

  redis:
    image: redis:7
    ports:
      - "6379:6379"

  control-plane:
    build:
      context: .
      dockerfile: apps/control-plane/Dockerfile
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/browsercloud
      REDIS_URL: redis://redis:6379
    ports:
      - "8080:8080"
    depends_on:
      - postgres
      - redis

  web-console:
    build:
      context: .
      dockerfile: apps/web-console/Dockerfile
    ports:
      - "3000:80"
    depends_on:
      - control-plane
```

Browser Node 在本地阶段建议直接运行在 Linux 宿主机，避免 Docker 内调试 Display、uinput 和网络 Namespace 过早复杂化。

---

# 24. Kubernetes 骨架

MVP 后再引入：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: control-plane
spec:
  replicas: 2
  selector:
    matchLabels:
      app: control-plane
  template:
    metadata:
      labels:
        app: control-plane
    spec:
      containers:
        - name: control-plane
          image: example/control-plane:0.1.0
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
```

Browser Node 使用独立 Node Pool，Node Agent 可使用 DaemonSet，但高权限 Helper 必须拆分。

---

# 25. Makefile 骨架

```makefile
.PHONY: build test lint compose-up compose-down

build:
	./gradlew -p apps/control-plane build
	cargo build --workspace --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console build

test:
	./gradlew -p apps/control-plane test
	cargo test --workspace --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console test

lint:
	./gradlew -p apps/control-plane check
	cargo fmt --check --manifest-path apps/browser-node/Cargo.toml
	cargo clippy --workspace --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console lint

compose-up:
	docker compose up -d

compose-down:
	docker compose down
```

---

# 26. 首个端到端流程

```text
POST /sessions
→ INSERT sessions
→ POST /sessions/{id}:start
→ Coordinator 创建 Start Operation
→ NodeCommand StartRuntime
→ Rust Runtime Supervisor 启动 Chromium
→ NodeEvent RuntimeStarted
→ Coordinator 提交 SessionContext
→ GET /sessions/{id}
→ Console 显示 Running
→ 用户打开 noVNC
→ POST /sessions/{id}:terminate
→ Node 停止 Chromium
→ Session 变为 Terminated
```

首个 Sprint 的目标就是让这个流程稳定工作。

---

# 27. 首批测试骨架

## Java

```java
@Test
void shouldRejectSecondActiveOperation() {
    operationRepository.insert(activeOperation("session-1"));

    assertThrows(
            ActiveOperationExistsException.class,
            () -> coordinator.handle(
                    new StartSession(
                            "session-1",
                            "runtime-1",
                            "idem-2"
                    )
            )
    );
}
```

## Rust

```rust
#[tokio::test]
async fn duplicate_command_returns_previous_result() {
    // 写入已处理 message_id。
    // 再次发送相同命令。
    // 断言不重复启动 Chromium。
}
```

## E2E

```text
Given a created session
When start is requested twice with the same idempotency key
Then only one Chromium process exists
And both API responses reference the same operation
```

---

# 28. 不要在初版实现的内容

暂缓：

- 自研完整 Actor Framework；
- 跨 Region；
- 全量 Kubernetes Operator；
- Deep Chromium Fork；
- 多 Runtime Provider；
- 全量 Extension Isolation；
- 完整 WebRTC Media Cluster；
- 全量 Cost Learning；
- 完整 Compliance Service；
- 多语言 SDK；
- 自动化复杂 Challenge 处理。

先证明核心闭环，再逐步替换模块实现。

---

# 29. 开始编码的建议顺序

```text
1. Contracts
2. Database
3. Session API
4. Coordinator
5. Node RPC
6. Runtime Supervisor
7. Browser Supervisor
8. noVNC / Input
9. Profile
10. State
11. Agent Tool
12. Prompt Security
13. Reliability
14. Kubernetes
```

代码骨架必须保持模块边界，但第一阶段可以在一个 Control Plane 进程中运行多个模块，避免过早微服务化。
