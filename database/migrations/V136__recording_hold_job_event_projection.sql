-- Additive hints for job details that can change while authority remains UNKNOWN.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
CREATE INDEX idx_recording_cloud_hold_exhausted_tenant
    ON recording_cloud_hold_jobs(tenant_id) WHERE state = 'UNKNOWN' AND attempt >= 10;
CREATE FUNCTION recording_cloud_hold_job_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR ROW(NEW.state, NEW.attempt, NEW.failure_code)
        IS DISTINCT FROM ROW(OLD.state, OLD.attempt, OLD.failure_code) THEN
      INSERT INTO enterprise_overview_events(tenant_id, change_type) VALUES(NEW.tenant_id, 'RETENTION');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_job_event AFTER INSERT OR UPDATE ON recording_cloud_hold_jobs
    FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_job_event();
