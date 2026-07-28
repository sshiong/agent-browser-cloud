ALTER TABLE browser_placements
  ADD COLUMN background_tabs_frozen BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN new_tabs_blocked BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN browser_placements.background_tabs_frozen IS
  'Authoritative Node-acknowledged CDP Page lifecycle policy; true means background Page Targets are frozen';

COMMENT ON COLUMN browser_placements.new_tabs_blocked IS
  'Authoritative Node-acknowledged CDP Target policy; true means Page Targets created after policy activation are closed';
