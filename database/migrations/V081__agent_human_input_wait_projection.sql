ALTER TABLE agent_tasks
  ADD COLUMN execution_wait_reason TEXT,
  ADD COLUMN execution_wait_since TIMESTAMPTZ;

ALTER TABLE agent_tasks
  ADD CONSTRAINT chk_agent_task_execution_wait_reason
  CHECK (execution_wait_reason IS NULL OR execution_wait_reason = 'HUMAN_INPUT_PRIORITY');

ALTER TABLE agent_tasks
  ADD CONSTRAINT chk_agent_task_execution_wait_consistency
  CHECK (
    (execution_wait_reason IS NULL AND execution_wait_since IS NULL)
    OR (execution_wait_reason IS NOT NULL AND execution_wait_since IS NOT NULL)
  );

COMMENT ON COLUMN agent_tasks.execution_wait_reason IS
  'Transient public execution arbitration reason; canonical task state remains RUNNING';
COMMENT ON COLUMN agent_tasks.execution_wait_since IS
  'First instant the current execution arbitration wait began';
