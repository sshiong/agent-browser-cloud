ALTER TABLE agent_outcome_verification_jobs
  ADD COLUMN semantic_evidence_hash TEXT;

ALTER TABLE agent_outcome_verification_jobs
  ADD CONSTRAINT chk_agent_outcome_semantic_evidence_hash
  CHECK (semantic_evidence_hash IS NULL OR semantic_evidence_hash ~ '^[a-f0-9]{64}$')
  NOT VALID;

ALTER TABLE agent_outcome_verification_jobs
  VALIDATE CONSTRAINT chk_agent_outcome_semantic_evidence_hash;

COMMENT ON COLUMN agent_outcome_verification_jobs.semantic_evidence_hash IS
  'Stable hash of minimized outcome evidence excluding sampling cursors and freshness counters; nullable only for N-1 writers.';
