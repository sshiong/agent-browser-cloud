-- V001__initial_control_plane.sql
-- 描述：初始化控制面数据库

-- sessions - Session 主表
CREATE TABLE sessions (
    id                  TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    profile_id          TEXT NOT NULL,
    region              TEXT NOT NULL,
    resource_class      TEXT NOT NULL DEFAULT 'L2',
    state               TEXT NOT NULL DEFAULT 'CREATED',
    policy_hash         TEXT NOT NULL DEFAULT '',
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    terminated_at       TIMESTAMPTZ
);

CREATE INDEX idx_sessions_tenant_id ON sessions(tenant_id);
CREATE INDEX idx_sessions_state ON sessions(state);
CREATE INDEX idx_sessions_created_at ON sessions(created_at);
CREATE INDEX idx_sessions_tenant_state ON sessions(tenant_id, state);

COMMENT ON TABLE sessions IS 'Session 主表';
COMMENT ON COLUMN sessions.id IS 'Session ID，格式 ses_ + 随机字符串';
COMMENT ON COLUMN sessions.tenant_id IS '租户 ID';
COMMENT ON COLUMN sessions.state IS 'Session 状态';

-- session_contexts - Session 上下文表
CREATE TABLE session_contexts (
    session_id          TEXT NOT NULL REFERENCES sessions(id),
    context_epoch       BIGINT NOT NULL,
    coordinator_term    BIGINT NOT NULL,
    node_id             TEXT,
    runtime_build_id    TEXT,
    isolation_profile_id TEXT,
    proxy_binding_id    TEXT,
    network_revision    BIGINT NOT NULL DEFAULT 0,
    browser_generation  BIGINT NOT NULL DEFAULT 0,
    resource_class      TEXT NOT NULL,
    policy_hash         TEXT NOT NULL,
    committed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, context_epoch)
);

CREATE INDEX idx_session_contexts_session_id ON session_contexts(session_id);

COMMENT ON TABLE session_contexts IS 'Session 上下文表';
COMMENT ON COLUMN session_contexts.context_epoch IS 'Context 版本号，核心环境变化时递增';

-- coordinator_ownership - Coordinator 所有权表
CREATE TABLE coordinator_ownership (
    session_id          TEXT PRIMARY KEY REFERENCES sessions(id),
    coordinator_owner   TEXT NOT NULL,
    coordinator_term    BIGINT NOT NULL,
    owner_heartbeat_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_coordinator_ownership_heartbeat ON coordinator_ownership(owner_heartbeat_at);

COMMENT ON TABLE coordinator_ownership IS 'Coordinator 所有权表';

-- exclusive_operations - 排他操作表
CREATE TABLE exclusive_operations (
    operation_id        TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL REFERENCES sessions(id),
    owner_type          TEXT NOT NULL,
    actor_id            TEXT,
    mode                TEXT NOT NULL,
    priority            INTEGER NOT NULL DEFAULT 0,
    operation_epoch     BIGINT NOT NULL,
    coordinator_term    BIGINT NOT NULL,
    context_epoch       BIGINT NOT NULL,
    workflow_id         TEXT,
    cancellable         BOOLEAN NOT NULL DEFAULT TRUE,
    preemptible         BOOLEAN NOT NULL DEFAULT FALSE,
    phase               TEXT NOT NULL,
    state               TEXT NOT NULL DEFAULT 'ACTIVE',
    deadline            TIMESTAMPTZ NOT NULL,
    allowed_capabilities JSONB DEFAULT '[]',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_active_operation_per_session
ON exclusive_operations(session_id)
WHERE state = 'ACTIVE';

CREATE INDEX idx_exclusive_operations_session_id ON exclusive_operations(session_id);
CREATE INDEX idx_exclusive_operations_state ON exclusive_operations(state);
CREATE INDEX idx_exclusive_operations_deadline ON exclusive_operations(deadline) WHERE state = 'ACTIVE';

COMMENT ON TABLE exclusive_operations IS '排他操作表';
COMMENT ON INDEX uq_active_operation_per_session IS '同一 Session 最多一个 ACTIVE 操作';

-- durable_workflows - 持久化工作流表
CREATE TABLE durable_workflows (
    workflow_id         TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL REFERENCES sessions(id),
    operation_id        TEXT NOT NULL,
    workflow_type       TEXT NOT NULL,
    attempt             INTEGER NOT NULL DEFAULT 1,
    priority            INTEGER NOT NULL DEFAULT 0,
    state               TEXT NOT NULL DEFAULT 'PENDING',
    phase               TEXT,
    worker_id           TEXT,
    coordinator_term    BIGINT NOT NULL,
    context_epoch       BIGINT NOT NULL,
    operation_epoch     BIGINT NOT NULL,
    dispatched_at       TIMESTAMPTZ,
    started_at          TIMESTAMPTZ,
    heartbeat_at        TIMESTAMPTZ,
    phase_deadline      TIMESTAMPTZ,
    operation_deadline  TIMESTAMPTZ,
    cancellation_epoch  BIGINT NOT NULL DEFAULT 0,
    idempotency_key     TEXT,
    external_receipt    TEXT,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_durable_workflows_session_id ON durable_workflows(session_id);
CREATE INDEX idx_durable_workflows_operation_id ON durable_workflows(operation_id);
CREATE INDEX idx_durable_workflows_state ON durable_workflows(state);
CREATE INDEX idx_durable_workflows_phase_deadline ON durable_workflows(phase_deadline) WHERE state = 'RUNNING';

COMMENT ON TABLE durable_workflows IS '持久化工作流表';

-- outbox_events - 事件发布表
CREATE TABLE outbox_events (
    event_id            TEXT PRIMARY KEY,
    aggregate_type      TEXT NOT NULL,
    aggregate_id        TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    schema_version      INTEGER NOT NULL DEFAULT 1,
    payload             JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    publish_attempts    INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error          TEXT,
    dead_lettered_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_unpublished
ON outbox_events(next_attempt_at, created_at)
WHERE published_at IS NULL AND dead_lettered_at IS NULL;
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);

COMMENT ON TABLE outbox_events IS '事件发布表（Transactional Outbox）';

-- inbox_events - 事件消费表
CREATE TABLE inbox_events (
    event_id            TEXT PRIMARY KEY,
    consumer_id         TEXT NOT NULL,
    processed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inbox_events_consumer ON inbox_events(consumer_id, processed_at);

COMMENT ON TABLE inbox_events IS '事件消费表（Inbox 去重）';

-- api_idempotency_records - API 幂等提交记录
CREATE TABLE api_idempotency_records (
    record_id           TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    operation_type      TEXT NOT NULL,
    idempotency_key     TEXT NOT NULL,
    request_hash        TEXT NOT NULL,
    resource_id         TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, operation_type, idempotency_key)
);

CREATE INDEX idx_api_idempotency_created_at
ON api_idempotency_records(created_at);

COMMENT ON TABLE api_idempotency_records IS 'API 幂等键权威提交记录';

-- audit_events - 审计事件表
CREATE TABLE audit_events (
    event_id            TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT,
    event_type          TEXT NOT NULL,
    actor_type          TEXT NOT NULL,
    actor_id            TEXT,
    resource_type       TEXT,
    resource_id         TEXT,
    action              TEXT NOT NULL,
    result              TEXT NOT NULL,
    details             JSONB DEFAULT '{}',
    ip_address          INET,
    user_agent          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_tenant_id ON audit_events(tenant_id, created_at);
CREATE INDEX idx_audit_events_session_id ON audit_events(session_id, created_at);
CREATE INDEX idx_audit_events_event_type ON audit_events(event_type, created_at);

COMMENT ON TABLE audit_events IS '审计事件表';

-- runtime_builds - Runtime 构建表
CREATE TABLE runtime_builds (
    build_id            TEXT PRIMARY KEY,
    engine              TEXT NOT NULL,
    version             TEXT NOT NULL,
    platform            TEXT NOT NULL,
    capabilities        JSONB DEFAULT '{}',
    resource_requirements JSONB DEFAULT '{}',
    security_tier       TEXT NOT NULL DEFAULT 'TIER_0',
    signature           TEXT,
    sbom_url            TEXT,
    regression_status   TEXT DEFAULT 'UNKNOWN',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    validated_at        TIMESTAMPTZ,
    released_at         TIMESTAMPTZ
);

CREATE INDEX idx_runtime_builds_engine ON runtime_builds(engine, version);

COMMENT ON TABLE runtime_builds IS 'Runtime 构建表';

-- profiles - Profile 元数据表
CREATE TABLE profiles (
    profile_id          TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    name                TEXT NOT NULL,
    description         TEXT,
    storage_path        TEXT NOT NULL,
    encryption_key_id   TEXT,
    latest_checkpoint_id TEXT,
    latest_checkpoint_epoch BIGINT,
    state               TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_profiles_tenant_id ON profiles(tenant_id);

COMMENT ON TABLE profiles IS 'Profile 元数据表';

-- proxy_allocations - 代理分配表
CREATE TABLE proxy_allocations (
    allocation_id       TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT REFERENCES sessions(id),
    provider            TEXT NOT NULL,
    endpoint            TEXT NOT NULL,
    protocol            TEXT NOT NULL,
    country             TEXT,
    city                TEXT,
    asn                 TEXT,
    ip_type             TEXT,
    credential_ref      TEXT,
    state               TEXT NOT NULL DEFAULT 'ALLOCATED',
    allocated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at         TIMESTAMPTZ
);

CREATE INDEX idx_proxy_allocations_session_id ON proxy_allocations(session_id);
CREATE INDEX idx_proxy_allocations_state ON proxy_allocations(state);

COMMENT ON TABLE proxy_allocations IS '代理分配表';
