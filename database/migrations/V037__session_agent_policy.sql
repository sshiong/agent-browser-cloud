-- Immutable Session Agent policy and task-time policy evidence.

ALTER TABLE sessions
  ADD COLUMN agent_policy TEXT NOT NULL DEFAULT 'BALANCED';

UPDATE sessions
SET agent_policy =
  CASE
    WHEN lower(COALESCE(metadata->>'agentEnabled', 'true')) = 'false' THEN 'DISABLED'
    WHEN lower(COALESCE(metadata->>'agentPolicy', 'balanced')) = 'restricted' THEN 'RESTRICTED'
    WHEN lower(COALESCE(metadata->>'agentPolicy', 'balanced')) = 'interactive' THEN 'INTERACTIVE'
    ELSE 'BALANCED'
  END;

ALTER TABLE sessions
  ADD CONSTRAINT chk_sessions_agent_policy
  CHECK (agent_policy IN ('DISABLED', 'RESTRICTED', 'BALANCED', 'INTERACTIVE'));

ALTER TABLE agent_tasks
  ADD COLUMN agent_policy TEXT NOT NULL DEFAULT 'BALANCED';

UPDATE agent_tasks task
SET agent_policy = session.agent_policy
FROM sessions session
WHERE session.id = task.session_id;

ALTER TABLE agent_tasks
  ADD CONSTRAINT chk_agent_tasks_agent_policy
  CHECK (agent_policy IN ('DISABLED', 'RESTRICTED', 'BALANCED', 'INTERACTIVE'));

COMMENT ON COLUMN sessions.agent_policy IS
  'Immutable create-time Agent capability and budget policy';

COMMENT ON COLUMN agent_tasks.agent_policy IS
  'Agent policy bound when the task plan was created';
