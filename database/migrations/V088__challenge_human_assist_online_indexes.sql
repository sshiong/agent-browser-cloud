CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_tasks_challenge_event
    ON agent_tasks(challenge_event_id)
    WHERE challenge_event_id IS NOT NULL;
