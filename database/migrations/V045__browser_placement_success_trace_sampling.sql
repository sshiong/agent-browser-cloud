ALTER TABLE browser_placements
  ADD COLUMN success_trace_sample_percent INTEGER NOT NULL DEFAULT 100;

ALTER TABLE browser_placements
  ADD CONSTRAINT chk_browser_placements_success_trace_sample_percent
  CHECK (success_trace_sample_percent BETWEEN 1 AND 100) NOT VALID;

ALTER TABLE browser_placements
  VALIDATE CONSTRAINT chk_browser_placements_success_trace_sample_percent;

COMMENT ON COLUMN browser_placements.success_trace_sample_percent IS
  'Authoritative Node-acknowledged sampling percentage for successful Session command traces; failures and mandatory evidence are never sampled by this control';
