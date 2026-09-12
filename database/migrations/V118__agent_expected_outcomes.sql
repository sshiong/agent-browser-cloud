-- Structured Expected Outcome declarations and deterministic final-state evaluations.
-- Raw match values are normalized and hashed before this boundary.

ALTER TABLE agent_tasks
    ADD COLUMN expected_outcomes JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN outcome_expected_results JSONB NOT NULL DEFAULT '[]';

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_expected_outcomes CHECK (
      jsonb_typeof(expected_outcomes) = 'array'
      AND jsonb_array_length(expected_outcomes) <= 10
    ),
    ADD CONSTRAINT chk_agent_expected_results CHECK (
      jsonb_typeof(outcome_expected_results) = 'array'
      AND jsonb_array_length(outcome_expected_results) <= 10
    );

COMMENT ON COLUMN agent_tasks.expected_outcomes IS
'At most ten canonical outcome declarations containing SHA-256 values instead of raw match text';

COMMENT ON COLUMN agent_tasks.outcome_expected_results IS
'Deterministic evaluations bound into the exact Outcome Verifier payload and decision';
