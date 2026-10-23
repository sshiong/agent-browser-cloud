-- Expand only. Existing logical holds remain logical; no historical cloud proof is invented.
SET LOCAL lock_timeout = '5s';
ALTER TABLE enterprise_retention_policies
    ADD COLUMN cloud_hold_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_recording_cloud_hold_policy CHECK (
      NOT cloud_hold_required OR data_class = 'REMOTE_DESKTOP_RECORDING');

CREATE TABLE recording_cloud_hold_authorities (
    tenant_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    recording_id TEXT PRIMARY KEY,
    desired_mode TEXT NOT NULL CHECK (desired_mode IN ('ON', 'OFF')),
    desired_epoch BIGINT NOT NULL DEFAULT 1 CHECK (desired_epoch > 0),
    applied_mode TEXT CHECK (applied_mode IN ('ON', 'OFF')),
    applied_epoch BIGINT,
    proof_hash TEXT,
    inventory_sha256 TEXT,
    object_version_count BIGINT,
    completed_at TIMESTAMPTZ,
    active_operation_id TEXT,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'UNKNOWN', 'VERIFIED_ON', 'VERIFIED_OFF', 'DELETED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, session_id, recording_id),
    FOREIGN KEY (tenant_id, session_id, recording_id)
      REFERENCES session_recordings(tenant_id, session_id, recording_id),
    CHECK ((applied_epoch IS NULL AND applied_mode IS NULL AND proof_hash IS NULL
            AND inventory_sha256 IS NULL AND object_version_count IS NULL AND completed_at IS NULL)
        OR (applied_epoch IS NOT NULL AND applied_epoch > 0 AND applied_epoch <= desired_epoch
            AND applied_mode IS NOT NULL AND proof_hash IS NOT NULL
            AND proof_hash ~ '^[0-9a-f]{64}$' AND inventory_sha256 IS NOT NULL
            AND inventory_sha256 ~ '^[0-9a-f]{64}$' AND object_version_count IS NOT NULL
            AND object_version_count > 0 AND completed_at IS NOT NULL)),
    CHECK (status NOT IN ('VERIFIED_ON', 'VERIFIED_OFF') OR
        (active_operation_id IS NULL AND applied_epoch IS NOT NULL AND applied_epoch = desired_epoch
         AND applied_mode = desired_mode AND status = 'VERIFIED_' || applied_mode))
);
CREATE INDEX idx_recording_cloud_hold_tenant_status
    ON recording_cloud_hold_authorities(tenant_id, status);

CREATE TABLE recording_cloud_hold_jobs (
    operation_id TEXT PRIMARY KEY CHECK (operation_id ~ '^rhj_[a-z0-9_]+$'),
    tenant_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    authority_epoch BIGINT NOT NULL CHECK (authority_epoch > 0),
    node_id TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    manifest_bytes BIGINT NOT NULL CHECK (manifest_bytes > 0),
    segment_count BIGINT NOT NULL CHECK (segment_count BETWEEN 1 AND 100000),
    mode TEXT NOT NULL CHECK (mode IN ('ON', 'OFF')),
    previous_proof_hash TEXT,
    execution_budget_millis BIGINT NOT NULL DEFAULT 30000 CHECK (execution_budget_millis BETWEEN 1000 AND 120000),
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'EXECUTING', 'UNKNOWN', 'COMMITTED')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 10),
    lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    proof_hash TEXT,
    inventory_sha256 TEXT,
    object_version_count BIGINT,
    failure_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, session_id, recording_id, authority_epoch),
    UNIQUE (tenant_id, session_id, recording_id, operation_id),
    FOREIGN KEY (tenant_id, session_id, recording_id)
      REFERENCES recording_cloud_hold_authorities(tenant_id, session_id, recording_id),
    CHECK (previous_proof_hash IS NULL OR previous_proof_hash ~ '^[0-9a-f]{64}$'),
    CHECK (mode <> 'OFF' OR previous_proof_hash IS NOT NULL),
    CHECK (state NOT IN ('EXECUTING', 'UNKNOWN', 'COMMITTED')
      OR (attempt > 0 AND lease_epoch > 0 AND started_at IS NOT NULL)),
    CHECK ((state = 'COMMITTED' AND completed_at IS NOT NULL AND failure_code IS NULL
              AND proof_hash IS NOT NULL AND proof_hash ~ '^[0-9a-f]{64}$'
              AND inventory_sha256 IS NOT NULL AND inventory_sha256 ~ '^[0-9a-f]{64}$'
              AND object_version_count IS NOT NULL
              AND object_version_count BETWEEN segment_count * 2 + 1 AND (segment_count * 2 + 1) * 2)
        OR (state <> 'COMMITTED' AND proof_hash IS NULL AND inventory_sha256 IS NULL
              AND object_version_count IS NULL AND completed_at IS NULL))
);
CREATE UNIQUE INDEX idx_recording_cloud_hold_one_active
    ON recording_cloud_hold_jobs(recording_id) WHERE state <> 'COMMITTED';
CREATE INDEX idx_recording_cloud_hold_jobs_ready
    ON recording_cloud_hold_jobs(available_at, created_at, operation_id) WHERE state <> 'COMMITTED';
ALTER TABLE recording_cloud_hold_authorities ADD CONSTRAINT fk_recording_cloud_hold_active
    FOREIGN KEY (tenant_id, session_id, recording_id, active_operation_id)
    REFERENCES recording_cloud_hold_jobs(tenant_id, session_id, recording_id, operation_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE recording_cloud_hold_rollups (
    tenant_id TEXT PRIMARY KEY,
    pending_count BIGINT NOT NULL DEFAULT 0 CHECK (pending_count >= 0),
    unknown_count BIGINT NOT NULL DEFAULT 0 CHECK (unknown_count >= 0),
    verified_on_count BIGINT NOT NULL DEFAULT 0 CHECK (verified_on_count >= 0),
    verified_off_count BIGINT NOT NULL DEFAULT 0 CHECK (verified_off_count >= 0)
);
CREATE FUNCTION recording_cloud_hold_rollup() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior TEXT := CASE WHEN TG_OP = 'INSERT' THEN '' ELSE OLD.status END;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status = OLD.status THEN RETURN NEW; END IF;
    INSERT INTO recording_cloud_hold_rollups(tenant_id) VALUES(NEW.tenant_id) ON CONFLICT DO NOTHING;
    UPDATE recording_cloud_hold_rollups SET
      pending_count = pending_count + (NEW.status = 'PENDING')::int - (prior = 'PENDING')::int,
      unknown_count = unknown_count + (NEW.status = 'UNKNOWN')::int - (prior = 'UNKNOWN')::int,
      verified_on_count = verified_on_count + (NEW.status = 'VERIFIED_ON')::int - (prior = 'VERIFIED_ON')::int,
      verified_off_count = verified_off_count + (NEW.status = 'VERIFIED_OFF')::int - (prior = 'VERIFIED_OFF')::int
    WHERE tenant_id = NEW.tenant_id;
    INSERT INTO enterprise_overview_events(tenant_id, change_type) VALUES(NEW.tenant_id, 'RETENTION');
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_rollup AFTER INSERT OR UPDATE ON recording_cloud_hold_authorities
    FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_rollup();

CREATE FUNCTION recording_cloud_hold_job_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'RECORDING_HOLD_JOB_IMMUTABLE'; END IF;
    IF TG_OP = 'INSERT' AND (NEW.state <> 'QUEUED' OR NEW.attempt <> 0 OR NEW.lease_epoch <> 0
        OR NOT EXISTS (SELECT 1 FROM recording_cloud_hold_authorities auth
          WHERE auth.recording_id = NEW.recording_id AND auth.tenant_id = NEW.tenant_id
            AND auth.session_id = NEW.session_id AND auth.active_operation_id IS NULL
            AND auth.desired_epoch = NEW.authority_epoch AND auth.desired_mode = NEW.mode
            AND auth.proof_hash IS NOT DISTINCT FROM NEW.previous_proof_hash)) THEN
      RAISE EXCEPTION 'RECORDING_HOLD_JOB_AUTHORITY_INVALID' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (
        OLD.state = 'COMMITTED' OR
        (NEW.state = 'COMMITTED' AND (OLD.state <> 'EXECUTING' OR NEW.lease_epoch = 0 OR NEW.attempt = 0)) OR
        ROW(NEW.operation_id, NEW.tenant_id, NEW.session_id, NEW.recording_id, NEW.authority_epoch,
            NEW.node_id, NEW.profile_id, NEW.manifest_sha256, NEW.manifest_bytes, NEW.segment_count,
            NEW.mode, NEW.previous_proof_hash, NEW.execution_budget_millis)
        IS DISTINCT FROM
        ROW(OLD.operation_id, OLD.tenant_id, OLD.session_id, OLD.recording_id, OLD.authority_epoch,
            OLD.node_id, OLD.profile_id, OLD.manifest_sha256, OLD.manifest_bytes, OLD.segment_count,
            OLD.mode, OLD.previous_proof_hash, OLD.execution_budget_millis)
        OR NEW.lease_epoch < OLD.lease_epoch OR NEW.attempt < OLD.attempt
        OR (OLD.state <> 'QUEUED' AND NEW.state = 'QUEUED')) THEN
      RAISE EXCEPTION 'RECORDING_HOLD_REQUEST_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_job_guard BEFORE INSERT OR UPDATE OR DELETE ON recording_cloud_hold_jobs
    FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_job_guard();

CREATE FUNCTION recording_cloud_hold_authority_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
      IF NEW.desired_mode <> 'ON' OR NEW.desired_epoch <> 1 OR NEW.applied_epoch IS NOT NULL
          OR NEW.active_operation_id IS NOT NULL OR NEW.status <> 'PENDING' THEN
        RAISE EXCEPTION 'RECORDING_HOLD_INITIAL_AUTHORITY_INVALID';
      END IF;
    ELSE
      IF ROW(NEW.tenant_id, NEW.session_id, NEW.recording_id)
          IS DISTINCT FROM ROW(OLD.tenant_id, OLD.session_id, OLD.recording_id)
          OR NEW.desired_epoch <> OLD.desired_epoch + (NEW.desired_mode <> OLD.desired_mode)::int THEN
        RAISE EXCEPTION 'RECORDING_HOLD_AUTHORITY_IDENTITY_IMMUTABLE';
      END IF;
      IF OLD.active_operation_id IS NOT NULL AND NEW.active_operation_id IS DISTINCT FROM OLD.active_operation_id
          AND NOT EXISTS (SELECT 1 FROM recording_cloud_hold_jobs
            WHERE operation_id = OLD.active_operation_id AND state = 'COMMITTED') THEN
        RAISE EXCEPTION 'RECORDING_HOLD_ACTIVE_RESULT_UNKNOWN' USING ERRCODE = '23514';
      END IF;
      IF ROW(NEW.applied_epoch, NEW.applied_mode, NEW.proof_hash, NEW.inventory_sha256,
             NEW.object_version_count, NEW.completed_at)
          IS DISTINCT FROM ROW(OLD.applied_epoch, OLD.applied_mode, OLD.proof_hash, OLD.inventory_sha256,
             OLD.object_version_count, OLD.completed_at)
          AND NOT EXISTS (SELECT 1 FROM recording_cloud_hold_jobs job
            WHERE job.operation_id = OLD.active_operation_id AND job.state = 'COMMITTED'
              AND ROW(job.authority_epoch, job.mode, job.proof_hash, job.inventory_sha256,
                      job.object_version_count, job.completed_at)
                  IS NOT DISTINCT FROM ROW(NEW.applied_epoch, NEW.applied_mode, NEW.proof_hash,
                      NEW.inventory_sha256, NEW.object_version_count, NEW.completed_at)) THEN
        RAISE EXCEPTION 'RECORDING_HOLD_COMMITTED_PROOF_REQUIRED' USING ERRCODE = '23514';
      END IF;
    END IF;
    IF NEW.status = 'DELETED' AND NOT EXISTS (SELECT 1 FROM session_recordings
        WHERE recording_id = NEW.recording_id AND deleted_at IS NOT NULL) THEN
      RAISE EXCEPTION 'RECORDING_HOLD_DELETION_PROOF_REQUIRED';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_authority_guard BEFORE INSERT OR UPDATE
    ON recording_cloud_hold_authorities FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_authority_guard();

-- Record -> authority -> job -> rollup is the lock order. Policy takes all record locks first.
CREATE FUNCTION recording_cloud_hold_advance(record_id TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE rec session_recordings%ROWTYPE;
DECLARE auth recording_cloud_hold_authorities%ROWTYPE;
DECLARE next_id TEXT;
BEGIN
    SELECT * INTO rec FROM session_recordings WHERE recording_id = record_id FOR UPDATE;
    SELECT * INTO auth FROM recording_cloud_hold_authorities WHERE recording_id = record_id FOR UPDATE;
    IF NOT FOUND OR rec.deleted_at IS NOT NULL THEN RETURN; END IF;
    IF auth.active_operation_id IS NULL AND auth.applied_epoch IS DISTINCT FROM auth.desired_epoch THEN
      next_id := 'rhj_' || replace(gen_random_uuid()::text, '-', '');
      INSERT INTO recording_cloud_hold_jobs(operation_id, tenant_id, session_id, recording_id,
          authority_epoch, node_id, profile_id, manifest_sha256, manifest_bytes, segment_count,
          mode, previous_proof_hash)
      SELECT next_id, rec.tenant_id, rec.session_id, rec.recording_id,
          auth.desired_epoch, rec.node_id, session.profile_id, rec.manifest_sha256,
          rec.manifest_bytes, rec.segment_count, auth.desired_mode, auth.proof_hash
      FROM sessions session WHERE session.id = rec.session_id AND session.tenant_id = rec.tenant_id;
      IF NOT FOUND THEN RAISE EXCEPTION 'RECORDING_HOLD_SESSION_SCOPE_INVALID'; END IF;
      UPDATE recording_cloud_hold_authorities SET active_operation_id = next_id,
          status = 'PENDING', updated_at = now() WHERE recording_id = record_id;
    END IF;
    UPDATE session_recordings SET legal_hold = NOT COALESCE((
        auth.desired_mode = 'OFF' AND auth.applied_mode = 'OFF'
        AND auth.applied_epoch = auth.desired_epoch AND auth.active_operation_id IS NULL
        AND auth.status = 'VERIFIED_OFF'), FALSE) WHERE recording_id = record_id;
END $$;

CREATE FUNCTION recording_cloud_hold_set_desired(record_id TEXT, wanted BOOLEAN)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE rec session_recordings%ROWTYPE;
DECLARE auth recording_cloud_hold_authorities%ROWTYPE;
DECLARE wanted_mode TEXT := CASE WHEN wanted THEN 'ON' ELSE 'OFF' END;
BEGIN
    SELECT * INTO rec FROM session_recordings WHERE recording_id = record_id FOR UPDATE;
    IF NOT FOUND OR rec.deleted_at IS NOT NULL THEN RETURN; END IF;
    SELECT * INTO auth FROM recording_cloud_hold_authorities WHERE recording_id = record_id FOR UPDATE;
    IF NOT FOUND THEN
      IF NOT wanted THEN RETURN; END IF;
      INSERT INTO recording_cloud_hold_authorities(tenant_id, session_id, recording_id, desired_mode, status)
          VALUES(rec.tenant_id, rec.session_id, record_id, wanted_mode, 'PENDING');
    ELSIF auth.desired_mode <> wanted_mode THEN
      UPDATE recording_cloud_hold_authorities SET desired_mode = wanted_mode,
          desired_epoch = desired_epoch + 1,
          status = CASE WHEN status = 'UNKNOWN' THEN 'UNKNOWN' ELSE 'PENDING' END,
          updated_at = now() WHERE recording_id = record_id;
    END IF;
    PERFORM recording_cloud_hold_advance(record_id);
END $$;

CREATE FUNCTION recording_cloud_hold_policy_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_tenant TEXT;
DECLARE source_class TEXT;
BEGIN
    source_tenant := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    source_class := CASE WHEN TG_OP = 'DELETE' THEN OLD.data_class ELSE NEW.data_class END;
    IF TG_OP = 'UPDATE' AND OLD.cloud_hold_required AND
        ROW(NEW.tenant_id, NEW.data_class) IS DISTINCT FROM ROW(OLD.tenant_id, OLD.data_class) THEN
      RAISE EXCEPTION 'RECORDING_CLOUD_HOLD_POLICY_IDENTITY_IMMUTABLE';
    END IF;
    IF source_class <> 'REMOTE_DESKTOP_RECORDING' THEN RETURN COALESCE(NEW, OLD); END IF;
    -- BEFORE INSERT also runs for an old UPSERT that omits the new column. Its conflict
    -- UPDATE is checked separately against the actual resulting row; never reject its default
    -- false before PostgreSQL has selected that path.
    IF TG_OP = 'INSERT' AND EXISTS (SELECT 1 FROM enterprise_retention_policies
        WHERE tenant_id = NEW.tenant_id AND data_class = NEW.data_class AND cloud_hold_required) THEN
      RETURN NEW;
    END IF;
    IF (TG_OP = 'DELETE' OR NOT NEW.cloud_hold_required) AND EXISTS (
        SELECT 1 FROM recording_cloud_hold_authorities WHERE tenant_id = source_tenant
          AND status NOT IN ('VERIFIED_OFF', 'DELETED')) THEN
      RAISE EXCEPTION 'RECORDING_CLOUD_HOLD_NOT_RELEASED' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_policy_guard BEFORE INSERT OR UPDATE OR DELETE
    ON enterprise_retention_policies FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_policy_guard();

CREATE FUNCTION recording_cloud_hold_policy_project() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE rec_id TEXT;
BEGIN
    IF NEW.data_class <> 'REMOTE_DESKTOP_RECORDING' OR NOT NEW.cloud_hold_required THEN RETURN NEW; END IF;
    PERFORM recording_id FROM session_recordings WHERE tenant_id = NEW.tenant_id AND deleted_at IS NULL
      ORDER BY recording_id FOR UPDATE;
    FOR rec_id IN SELECT recording_id FROM session_recordings
        WHERE tenant_id = NEW.tenant_id AND deleted_at IS NULL ORDER BY recording_id LOOP
      PERFORM recording_cloud_hold_set_desired(rec_id, NEW.legal_hold);
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_policy_project AFTER INSERT OR UPDATE ON enterprise_retention_policies
    FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_policy_project();

-- Alphabetically before the V134 guard, including older code that writes legal_hold = false.
CREATE FUNCTION recording_cloud_hold_record_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM recording_cloud_hold_authorities auth WHERE auth.recording_id = NEW.recording_id
        AND NOT COALESCE((auth.desired_mode = 'OFF' AND auth.applied_mode = 'OFF'
                 AND auth.applied_epoch = auth.desired_epoch AND auth.active_operation_id IS NULL
                 AND auth.status = 'VERIFIED_OFF'), FALSE)) THEN
      NEW.legal_hold := TRUE;
      IF NEW.deletion_authorized_at IS NOT NULL AND OLD.deletion_authorized_at IS NULL THEN
        RAISE EXCEPTION 'RECORDING_CLOUD_HOLD_NOT_RELEASED' USING ERRCODE = '23514';
      END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_record_guard BEFORE UPDATE ON session_recordings
    FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_record_guard();

CREATE FUNCTION recording_cloud_hold_record_project() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
      IF EXISTS (SELECT 1 FROM enterprise_retention_policies WHERE tenant_id = NEW.tenant_id
          AND data_class = 'REMOTE_DESKTOP_RECORDING' AND cloud_hold_required AND legal_hold) THEN
        PERFORM recording_cloud_hold_set_desired(NEW.recording_id, TRUE);
      END IF;
    ELSIF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL THEN
      UPDATE recording_cloud_hold_authorities SET status = 'DELETED', updated_at = now()
          WHERE recording_id = NEW.recording_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recording_cloud_hold_record_project AFTER INSERT OR UPDATE OF deleted_at ON session_recordings
    FOR EACH ROW EXECUTE FUNCTION recording_cloud_hold_record_project();

COMMENT ON TABLE recording_cloud_hold_jobs IS
  'Immutable business request, distinct recovery lease epoch; unknown mutations cannot be superseded';
COMMENT ON COLUMN enterprise_retention_policies.cloud_hold_required IS
  'Explicit opt-in to object version protection; saving desired policy is not verified cloud protection';
