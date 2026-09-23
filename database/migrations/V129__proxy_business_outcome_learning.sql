-- Learn bounded Proxy route quality only from independently verified Agent outcomes.
--
-- The observation ledger is append-only and idempotent by verification_id.  It intentionally
-- stores no goal, page content, URL, credential, model output, or capability.  Aggregate evidence
-- is tenant/Binding scoped and remains advisory: health, region, capacity and provider identity
-- continue to be hard admission gates.

CREATE TABLE proxy_route_business_outcomes (
    verification_id      TEXT PRIMARY KEY,
    tenant_id            TEXT NOT NULL,
    session_id           TEXT NOT NULL,
    task_id              TEXT NOT NULL,
    binding_profile_id   TEXT NOT NULL,
    provider_id          TEXT NOT NULL,
    decision             TEXT NOT NULL,
    reason_codes         JSONB NOT NULL DEFAULT '[]'::jsonb,
    observed_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_proxy_route_business_task UNIQUE (tenant_id, task_id),
    CONSTRAINT fk_proxy_route_business_binding
      FOREIGN KEY (binding_profile_id, tenant_id)
      REFERENCES proxy_binding_profiles(binding_profile_id, tenant_id),
    CONSTRAINT fk_proxy_route_business_session
      FOREIGN KEY (session_id, tenant_id)
      REFERENCES sessions(id, tenant_id),
    CONSTRAINT chk_proxy_route_business_decision
      CHECK (decision IN ('VERIFIED', 'NOT_VERIFIED')),
    CONSTRAINT chk_proxy_route_business_reasons
      CHECK (jsonb_typeof(reason_codes) = 'array' AND jsonb_array_length(reason_codes) BETWEEN 1 AND 16)
);

CREATE TABLE proxy_route_business_stats (
    binding_profile_id   TEXT PRIMARY KEY,
    tenant_id            TEXT NOT NULL,
    provider_id          TEXT NOT NULL,
    sample_count         BIGINT NOT NULL DEFAULT 0,
    verified_count       BIGINT NOT NULL DEFAULT 0,
    rejected_count       BIGINT NOT NULL DEFAULT 0,
    success_ewma         NUMERIC(8, 7),
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_observed_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_proxy_route_business_stats_binding
      FOREIGN KEY (binding_profile_id, tenant_id)
      REFERENCES proxy_binding_profiles(binding_profile_id, tenant_id),
    CONSTRAINT chk_proxy_route_business_stats_counts CHECK (
      sample_count = verified_count + rejected_count
      AND sample_count > 0
      AND verified_count >= 0
      AND rejected_count >= 0
      AND consecutive_failures >= 0
      AND consecutive_failures <= rejected_count
    ),
    CONSTRAINT chk_proxy_route_business_stats_ewma
      CHECK (success_ewma BETWEEN 0 AND 1)
);

ALTER TABLE session_proxy_binding_assignments
  ADD COLUMN selection_reason TEXT;

ALTER TABLE session_proxy_binding_assignments
  ADD CONSTRAINT chk_session_proxy_binding_selection_reason CHECK (
    (selection_mode = 'EXPLICIT' AND selection_reason IS NULL)
    OR
    (selection_mode = 'AUTO' AND (
      selection_reason IS NULL
      OR selection_reason IN ('SCORE', 'PROFILE_STICKY', 'CONSTRAINED_EXPLORATION')
    ))
  ) NOT VALID;

ALTER TABLE session_proxy_binding_assignments
  VALIDATE CONSTRAINT chk_session_proxy_binding_selection_reason;

CREATE INDEX idx_proxy_route_business_outcomes_binding
  ON proxy_route_business_outcomes(tenant_id, binding_profile_id, observed_at DESC);

COMMENT ON TABLE proxy_route_business_outcomes IS
  'Minimal idempotent outcome ledger sourced only from the independent Outcome Verifier';
COMMENT ON TABLE proxy_route_business_stats IS
  'Bounded advisory business-success evidence; never bypasses route health or policy gates';
COMMENT ON COLUMN session_proxy_binding_assignments.selection_reason IS
  'Immutable AUTO route strategy: score, safe Profile stickiness, or deterministic constrained exploration';
