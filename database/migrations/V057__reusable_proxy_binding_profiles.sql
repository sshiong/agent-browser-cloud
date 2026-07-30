-- Reusable tenant-scoped Proxy Binding configuration.
--
-- A profile is management-plane configuration. Each Session snapshots the selected profile and
-- still receives its own runtime allocation, so allocations are never shared across Sessions.
-- All changes are additive for rolling N-1 compatibility.

CREATE TABLE proxy_binding_profiles (
  binding_profile_id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  provider_id TEXT NOT NULL,
  region TEXT,
  expected_exit_ip TEXT NOT NULL,
  credential_ref TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  health_state TEXT NOT NULL DEFAULT 'UNVERIFIED',
  last_verified_exit_ip TEXT,
  last_health_checked_at TIMESTAMPTZ,
  last_failure_reason TEXT,
  created_by TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT proxy_binding_profiles_tenant_identity UNIQUE (binding_profile_id, tenant_id)
);

CREATE UNIQUE INDEX uq_proxy_binding_profiles_tenant_name
  ON proxy_binding_profiles (tenant_id, lower(name));

CREATE INDEX idx_proxy_binding_profiles_tenant_updated
  ON proxy_binding_profiles (tenant_id, updated_at DESC);

ALTER TABLE proxy_binding_profiles
  ADD CONSTRAINT proxy_binding_profiles_health_state_check
    CHECK (health_state IN ('UNVERIFIED', 'HEALTHY', 'UNHEALTHY', 'DISABLED')) NOT VALID,
  ADD CONSTRAINT proxy_binding_profiles_name_check
    CHECK (length(btrim(name)) BETWEEN 1 AND 96) NOT VALID,
  ADD CONSTRAINT proxy_binding_profiles_credential_ref_check
    CHECK (
      length(btrim(credential_ref)) BETWEEN 1 AND 512
      AND credential_ref ~ '^(vault|secret|aws-sm|gcp-sm|azure-kv)://[^[:space:]]+$'
    ) NOT VALID;

ALTER TABLE proxy_binding_profiles
  VALIDATE CONSTRAINT proxy_binding_profiles_health_state_check;

ALTER TABLE proxy_binding_profiles
  VALIDATE CONSTRAINT proxy_binding_profiles_name_check;

ALTER TABLE proxy_binding_profiles
  VALIDATE CONSTRAINT proxy_binding_profiles_credential_ref_check;

CREATE TABLE session_proxy_binding_assignments (
  session_id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  binding_profile_id TEXT NOT NULL,
  binding_version BIGINT NOT NULL,
  provider_id TEXT NOT NULL,
  region TEXT,
  expected_exit_ip TEXT NOT NULL,
  credential_ref TEXT NOT NULL,
  assigned_by TEXT NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_session_proxy_bindings_tenant_profile
  ON session_proxy_binding_assignments (tenant_id, binding_profile_id, assigned_at DESC);

ALTER TABLE session_proxy_binding_assignments
  ADD CONSTRAINT session_proxy_binding_assignments_session_fk
    FOREIGN KEY (session_id, tenant_id)
    REFERENCES sessions(id, tenant_id)
    ON DELETE CASCADE NOT VALID,
  ADD CONSTRAINT session_proxy_binding_assignments_profile_fk
    FOREIGN KEY (binding_profile_id, tenant_id)
    REFERENCES proxy_binding_profiles(binding_profile_id, tenant_id)
    ON DELETE RESTRICT NOT VALID;

ALTER TABLE session_proxy_binding_assignments
  VALIDATE CONSTRAINT session_proxy_binding_assignments_session_fk;

ALTER TABLE session_proxy_binding_assignments
  VALIDATE CONSTRAINT session_proxy_binding_assignments_profile_fk;

ALTER TABLE proxy_allocations
  ADD COLUMN binding_profile_id TEXT,
  ADD COLUMN binding_version BIGINT,
  ADD COLUMN expected_exit_ip TEXT;

ALTER TABLE proxy_allocations
  ADD CONSTRAINT proxy_allocations_binding_profile_fk
    FOREIGN KEY (binding_profile_id, tenant_id)
    REFERENCES proxy_binding_profiles(binding_profile_id, tenant_id)
    ON DELETE RESTRICT NOT VALID,
  ADD CONSTRAINT proxy_allocations_binding_snapshot_check
    CHECK (
      (binding_profile_id IS NULL AND binding_version IS NULL)
      OR
      (binding_profile_id IS NOT NULL AND binding_version IS NOT NULL)
    ) NOT VALID;

ALTER TABLE proxy_allocations
  VALIDATE CONSTRAINT proxy_allocations_binding_profile_fk;

ALTER TABLE proxy_allocations
  VALIDATE CONSTRAINT proxy_allocations_binding_snapshot_check;

CREATE INDEX idx_proxy_allocations_binding_profile
  ON proxy_allocations (tenant_id, binding_profile_id, allocated_at DESC)
  WHERE binding_profile_id IS NOT NULL;
