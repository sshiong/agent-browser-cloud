CREATE OR REPLACE FUNCTION notify_worker_job_ready()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    ready_states TEXT[] := string_to_array(TG_ARGV[1], ',');
    job_id TEXT := to_jsonb(NEW) ->> TG_ARGV[2];
BEGIN
    IF NEW.state = ANY(ready_states) THEN
        IF TG_OP = 'INSERT'
           OR (
               TG_OP = 'UPDATE'
               AND (
                   OLD.state IS DISTINCT FROM NEW.state
                   OR OLD.available_at IS DISTINCT FROM NEW.available_at
               )
           ) THEN
            PERFORM pg_notify('browsercloud_worker_jobs', TG_ARGV[0] || ':' || job_id);
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_execution_job_ready_notify
AFTER INSERT OR UPDATE OF state, available_at ON agent_execution_jobs
FOR EACH ROW EXECUTE FUNCTION notify_worker_job_ready('agent-execution', 'QUEUED', 'job_id');

CREATE TRIGGER trg_agent_review_job_ready_notify
AFTER INSERT OR UPDATE OF state, available_at ON agent_review_jobs
FOR EACH ROW EXECUTE FUNCTION notify_worker_job_ready('agent-review', 'QUEUED', 'job_id');

CREATE TRIGGER trg_agent_outcome_job_ready_notify
AFTER INSERT OR UPDATE OF state, available_at ON agent_outcome_verification_jobs
FOR EACH ROW EXECUTE FUNCTION notify_worker_job_ready('agent-outcome', 'QUEUED', 'job_id');

CREATE TRIGGER trg_challenge_visual_job_ready_notify
AFTER INSERT OR UPDATE OF state, available_at ON challenge_visual_jobs
FOR EACH ROW EXECUTE FUNCTION notify_worker_job_ready('challenge-visual', 'READY', 'job_id');

CREATE TRIGGER trg_runtime_validation_job_ready_notify
AFTER INSERT OR UPDATE OF state, available_at ON runtime_validation_jobs
FOR EACH ROW EXECUTE FUNCTION notify_worker_job_ready('runtime-validation', 'QUEUED', 'validation_id');

CREATE TRIGGER trg_recovery_gameday_job_ready_notify
AFTER INSERT OR UPDATE OF state, available_at ON recovery_gameday_jobs
FOR EACH ROW EXECUTE FUNCTION notify_worker_job_ready(
    'recovery-gameday', 'QUEUED,RECOVERY_REQUIRED', 'gameday_id'
);

COMMENT ON FUNCTION notify_worker_job_ready() IS
'Payload-minimized transactional wakeup for bounded Worker claim long-polls; queue tables remain authoritative';
