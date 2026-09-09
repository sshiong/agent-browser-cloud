-- Data-minimized, append-only execution memory kept separate from mutable Task and Browser State.
CREATE TABLE agent_task_memory_events (
    event_id           TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    session_id         TEXT NOT NULL,
    task_id            TEXT NOT NULL REFERENCES agent_tasks(task_id) ON DELETE CASCADE,
    memory_sequence    INTEGER NOT NULL CHECK (memory_sequence > 0),
    event_key          TEXT NOT NULL CHECK (length(event_key) <= 256),
    event_type         TEXT NOT NULL,
    plan_intent_id     TEXT CHECK (plan_intent_id IS NULL OR length(plan_intent_id) <= 128),
    step_ordinal       INTEGER CHECK (step_ordinal IS NULL OR step_ordinal >= 0),
    step_id            TEXT CHECK (step_id IS NULL OR length(step_id) <= 128),
    tool_id            TEXT CHECK (tool_id IS NULL OR length(tool_id) <= 64),
    semantic_key       TEXT CHECK (semantic_key IS NULL OR length(semantic_key) = 64),
    status             TEXT CHECK (status IS NULL OR length(status) <= 64),
    result_hash        TEXT CHECK (result_hash IS NULL OR length(result_hash) <= 128),
    verification_code TEXT CHECK (verification_code IS NULL OR length(verification_code) <= 128),
    reason_code        TEXT,
    state_version      BIGINT CHECK (state_version IS NULL OR state_version >= 0),
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_agent_task_memory_session
      FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_task_memory_sequence UNIQUE (task_id, memory_sequence),
    CONSTRAINT uq_agent_task_memory_event_key UNIQUE (task_id, event_key),
    CONSTRAINT chk_agent_task_memory_event_type
      CHECK (event_type IN ('STEP_RESULT', 'STEP_FAILURE', 'REPLAN')),
    CONSTRAINT chk_agent_task_memory_reason_code
      CHECK (reason_code IS NULL OR length(reason_code) <= 128)
);

CREATE INDEX idx_agent_task_memory_tenant_task
  ON agent_task_memory_events (tenant_id, task_id, memory_sequence);

-- Preserve completed work for tasks created before V116. Legacy rows cannot reconstruct the
-- normalized action semantic key, so use a stable data-minimized compatibility fingerprint.
INSERT INTO agent_task_memory_events (
  event_id, tenant_id, session_id, task_id, memory_sequence, event_key, event_type,
  plan_intent_id, step_ordinal, step_id, tool_id, semantic_key, status, result_hash,
  verification_code, created_at
)
SELECT
  'atm_legacy_' || md5(task.task_id || ':' || result.ordinality::text),
  task.tenant_id,
  task.session_id,
  task.task_id,
  result.ordinality::integer,
  'legacy-result:' || result.ordinality::text,
  'STEP_RESULT',
  task.plan ->> 'intentId',
  (result.ordinality - 1)::integer,
  result.value ->> 'stepId',
  result.value ->> 'toolId',
  md5(task.task_id || ':' || coalesce(result.value ->> 'stepId', result.ordinality::text)) ||
    md5('legacy:' || task.task_id || ':' || coalesce(result.value ->> 'stepId', result.ordinality::text)),
  result.value ->> 'status',
  result.value ->> 'resultHash',
  result.value ->> 'verification',
  task.updated_at
FROM agent_tasks AS task
CROSS JOIN LATERAL jsonb_array_elements(task.execution_results)
  WITH ORDINALITY AS result(value, ordinality);

COMMENT ON TABLE agent_task_memory_events IS
  'Append-only, data-minimized Agent execution memory; Browser State and mutable Task remain separate authorities';

COMMENT ON COLUMN agent_task_memory_events.semantic_key IS
  'Normalized step fingerprint excluding plaintext, sealed Secret, capability material and ephemeral IDs';
