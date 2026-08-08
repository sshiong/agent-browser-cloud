-- Persist immutable provider economics and automatic route decisions.
--
-- Provider configuration remains supplied by the mounted, root-controlled catalog.  The values
-- below are copied into tenant Binding profiles and Session assignments so a catalog reload cannot
-- rewrite the evidence used for an already-created Session.

ALTER TABLE proxy_binding_profiles
  ADD COLUMN cost_per_gib_usd NUMERIC(10, 4) NOT NULL DEFAULT 0,
  ADD COLUMN reputation_score INTEGER NOT NULL DEFAULT 50,
  ADD COLUMN max_concurrent_sessions INTEGER NOT NULL DEFAULT 10000;

ALTER TABLE proxy_binding_profiles
  ADD CONSTRAINT proxy_binding_profiles_cost_check
    CHECK (cost_per_gib_usd >= 0 AND cost_per_gib_usd <= 10000) NOT VALID,
  ADD CONSTRAINT proxy_binding_profiles_reputation_check
    CHECK (reputation_score BETWEEN 0 AND 100) NOT VALID,
  ADD CONSTRAINT proxy_binding_profiles_capacity_check
    CHECK (max_concurrent_sessions BETWEEN 1 AND 1000000) NOT VALID;

ALTER TABLE proxy_binding_profiles
  VALIDATE CONSTRAINT proxy_binding_profiles_cost_check;

ALTER TABLE proxy_binding_profiles
  VALIDATE CONSTRAINT proxy_binding_profiles_reputation_check;

ALTER TABLE proxy_binding_profiles
  VALIDATE CONSTRAINT proxy_binding_profiles_capacity_check;

ALTER TABLE session_proxy_binding_assignments
  ADD COLUMN selection_mode TEXT NOT NULL DEFAULT 'EXPLICIT',
  ADD COLUMN routing_score NUMERIC(7, 3),
  ADD COLUMN quality_score INTEGER,
  ADD COLUMN reputation_score INTEGER,
  ADD COLUMN cost_per_gib_usd NUMERIC(10, 4),
  ADD COLUMN active_reservations INTEGER,
  ADD COLUMN max_concurrent_sessions INTEGER,
  ADD COLUMN candidate_scores JSONB;

ALTER TABLE session_proxy_binding_assignments
  ADD CONSTRAINT session_proxy_binding_selection_mode_check
    CHECK (selection_mode IN ('EXPLICIT', 'AUTO')) NOT VALID,
  ADD CONSTRAINT session_proxy_binding_routing_snapshot_check
    CHECK (
      (selection_mode = 'EXPLICIT'
        AND routing_score IS NULL
        AND quality_score IS NULL
        AND reputation_score IS NULL
        AND cost_per_gib_usd IS NULL
        AND active_reservations IS NULL
        AND max_concurrent_sessions IS NULL
        AND candidate_scores IS NULL)
      OR
      (selection_mode = 'AUTO'
        AND routing_score BETWEEN 0 AND 100
        AND quality_score BETWEEN 0 AND 100
        AND reputation_score BETWEEN 0 AND 100
        AND cost_per_gib_usd BETWEEN 0 AND 10000
        AND active_reservations >= 0
        AND max_concurrent_sessions BETWEEN 1 AND 1000000
        AND active_reservations < max_concurrent_sessions
        AND jsonb_typeof(candidate_scores) = 'array'
        AND jsonb_array_length(
          CASE
            WHEN jsonb_typeof(candidate_scores) = 'array' THEN candidate_scores
            ELSE '[]'::jsonb
          END
        ) BETWEEN 1 AND 256)
    ) NOT VALID;

ALTER TABLE session_proxy_binding_assignments
  VALIDATE CONSTRAINT session_proxy_binding_selection_mode_check;

ALTER TABLE session_proxy_binding_assignments
  VALIDATE CONSTRAINT session_proxy_binding_routing_snapshot_check;

CREATE INDEX idx_session_proxy_binding_provider_reservations
  ON session_proxy_binding_assignments (tenant_id, provider_id, credential_ref, assigned_at DESC);

COMMENT ON COLUMN session_proxy_binding_assignments.routing_score IS
  'Immutable AUTO decision score: quality 45%, reputation 20%, cost 15%, region 10%, headroom 10%.';
