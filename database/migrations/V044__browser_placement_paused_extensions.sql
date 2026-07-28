ALTER TABLE browser_placements
  ADD COLUMN paused_extension_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE browser_placements
  ADD CONSTRAINT chk_browser_placements_paused_extension_ids_array
  CHECK (jsonb_typeof(paused_extension_ids) = 'array') NOT VALID;

ALTER TABLE browser_placements
  VALIDATE CONSTRAINT chk_browser_placements_paused_extension_ids_array;

COMMENT ON COLUMN browser_placements.paused_extension_ids IS
  'Authoritative Node-acknowledged extension IDs whose background CDP targets are paused by the maximum resource policy';
