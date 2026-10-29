-- Expand accepted metadata versions without rewriting legacy rows or claiming v2 proof for them.
-- Keep this short DDL transaction separate from V138's table validation scan.
SET LOCAL lock_timeout = '5s';
ALTER TABLE session_recordings DROP CONSTRAINT chk_session_recording_manifest;
ALTER TABLE session_recordings ADD CONSTRAINT chk_session_recording_manifest CHECK (
    redaction_policy_version IN (1, 2)
    AND manifest_sha256 ~ '^[0-9a-f]{64}$'
    AND manifest_bytes > 0
    AND ended_at >= started_at
    AND retention_until >= ended_at
) NOT VALID;
