ALTER TABLE agent_tasks
    ADD COLUMN replan_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pending_state_version BIGINT;

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_tasks_replan_count
        CHECK (replan_count >= 0),
    ADD CONSTRAINT chk_agent_tasks_pending_state_version
        CHECK (pending_state_version IS NULL OR pending_state_version >= 0);
