-- Old workers may have dispatched a request whose transaction later rolled back, even at
-- attempt=0. Drain/reconcile the legacy queue before this migration; never infer preservation.
SET LOCAL lock_timeout = '5s';
LOCK TABLE recording_retention_deletion_jobs, session_recordings IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM recording_retention_deletion_jobs job
        JOIN session_recordings recording USING (tenant_id, session_id, recording_id)
        WHERE recording.deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'RECORDING_LEGACY_DELETION_RECONCILIATION_REQUIRED';
    END IF;
END $$;

ALTER TABLE session_recordings ADD COLUMN deletion_authorized_at TIMESTAMPTZ;
COMMENT ON COLUMN session_recordings.deletion_authorized_at IS
  'Irreversible deletion intent committed before Node dispatch; not a physical deletion receipt';

-- One lock namespace for policy/ingestion/authorization ordering, including older writers.
-- Shared locks permit parallel deletion claims. Policy writes take the exclusive side first.
CREATE FUNCTION recording_policy_write_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(1111706692, 134);
    RETURN NULL;
END $$;
CREATE TRIGGER recording_policy_write_lock BEFORE INSERT OR UPDATE OR DELETE
ON enterprise_retention_policies FOR EACH STATEMENT EXECUTE FUNCTION recording_policy_write_lock();

CREATE FUNCTION recording_ingest_policy_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(1111706692, 134);
    RETURN NULL;
END $$;
CREATE TRIGGER recording_ingest_policy_lock BEFORE INSERT
ON session_recordings FOR EACH STATEMENT EXECUTE FUNCTION recording_ingest_policy_lock();

CREATE FUNCTION recording_policy_preservation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.data_class = 'REMOTE_DESKTOP_RECORDING' AND EXISTS (
        SELECT 1 FROM session_recordings recording
        WHERE recording.tenant_id = NEW.tenant_id AND recording.deleted_at IS NULL
          AND recording.deletion_authorized_at IS NOT NULL
          AND (NEW.legal_hold OR recording.ended_at + make_interval(days => NEW.retention_days)
               > recording.retention_until)
    ) THEN
        RAISE EXCEPTION 'RECORDING_DELETION_ALREADY_AUTHORIZED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_policy_preservation_guard BEFORE INSERT OR UPDATE
ON enterprise_retention_policies FOR EACH ROW EXECUTE FUNCTION recording_policy_preservation_guard();

CREATE FUNCTION recording_deletion_authority_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE policy enterprise_retention_policies%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO policy FROM enterprise_retention_policies
        WHERE tenant_id = NEW.tenant_id AND data_class = 'REMOTE_DESKTOP_RECORDING';
        IF FOUND THEN
            NEW.legal_hold := NEW.legal_hold OR policy.legal_hold;
            NEW.retention_until := GREATEST(NEW.retention_until,
                NEW.ended_at + make_interval(days => policy.retention_days));
        END IF;
        IF NEW.deletion_authorized_at IS NOT NULL THEN
            RAISE EXCEPTION 'RECORDING_DELETION_AUTHORITY_REQUIRES_EXISTING_RECORDING';
        END IF;
    ELSE
        IF OLD.deletion_authorized_at IS NOT NULL THEN
            IF NEW.deletion_authorized_at IS DISTINCT FROM OLD.deletion_authorized_at
               OR (OLD.deleted_at IS NULL AND
                   (NEW.legal_hold OR NEW.retention_until > OLD.retention_until)) THEN
                RAISE EXCEPTION 'RECORDING_DELETION_ALREADY_AUTHORIZED' USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.deletion_authorized_at IS NOT NULL THEN
            IF NEW.legal_hold OR NEW.retention_until > now() OR NEW.deleted_at IS NOT NULL
                OR EXISTS (SELECT 1 FROM enterprise_retention_policies
                           WHERE tenant_id = NEW.tenant_id
                             AND data_class = 'REMOTE_DESKTOP_RECORDING' AND legal_hold) THEN
                RAISE EXCEPTION 'RECORDING_DELETION_NOT_AUTHORIZED' USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_deletion_authority_guard BEFORE INSERT OR UPDATE
ON session_recordings FOR EACH ROW EXECUTE FUNCTION recording_deletion_authority_guard();

-- N-1 workers cannot initiate deletion without the new durable authorization phase.
CREATE FUNCTION recording_job_authority_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state = 'EXECUTING' AND NOT EXISTS (
        SELECT 1 FROM session_recordings recording
        WHERE recording.tenant_id = NEW.tenant_id AND recording.session_id = NEW.session_id
          AND recording.recording_id = NEW.recording_id
          AND recording.deletion_authorized_at IS NOT NULL
          AND recording.deleted_at IS NULL AND NOT recording.legal_hold
    ) THEN
        RAISE EXCEPTION 'RECORDING_DURABLE_DELETION_AUTHORITY_REQUIRED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_job_authority_guard BEFORE INSERT OR UPDATE
ON recording_retention_deletion_jobs FOR EACH ROW EXECUTE FUNCTION recording_job_authority_guard();
