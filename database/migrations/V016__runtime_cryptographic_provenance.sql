ALTER TABLE runtime_builds
    ADD COLUMN artifact_digest TEXT,
    ADD COLUMN signing_key_id TEXT;

UPDATE runtime_builds
SET artifact_digest = 'sha256:' || repeat('0', 64),
    signing_key_id = 'local-development'
WHERE build_id = 'runtime_local_chromium';

COMMENT ON COLUMN runtime_builds.artifact_digest IS
'Immutable sha256 digest of the released Runtime artifact';

COMMENT ON COLUMN runtime_builds.signing_key_id IS
'Trusted Ed25519 release-signing key identifier; private key material is never stored here';
