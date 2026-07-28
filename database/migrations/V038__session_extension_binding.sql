-- Immutable Session Extension binding projected independently from mutable Placement state.

ALTER TABLE sessions
  ADD COLUMN extension_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE sessions session
SET extension_ids = demand.extension_ids
FROM session_resource_demands demand
WHERE demand.session_id = session.id;

ALTER TABLE sessions
  ADD CONSTRAINT chk_sessions_extension_ids
  CHECK (
    jsonb_typeof(extension_ids) = 'array'
    AND jsonb_array_length(extension_ids) <= 32
  );

COMMENT ON COLUMN sessions.extension_ids IS
  'Immutable normalized Extension IDs bound at Session creation';
