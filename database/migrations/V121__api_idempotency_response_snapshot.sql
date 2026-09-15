-- Exact response replay for mutation APIs whose resource projection can continue changing after
-- the original transaction commits. Existing idempotency records remain valid with no backfill.
ALTER TABLE api_idempotency_records
  ADD COLUMN response_payload TEXT;

COMMENT ON COLUMN api_idempotency_records.response_payload IS
  'Optional first committed API response snapshot used for exact idempotent replay';
