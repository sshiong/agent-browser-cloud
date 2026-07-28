ALTER TABLE browser_placements
  ADD COLUMN observer_frame_rate_fps INTEGER NOT NULL DEFAULT 0;

UPDATE browser_placements
SET observer_frame_rate_fps = 30
WHERE requires_desktop;

ALTER TABLE browser_placements
  ADD CONSTRAINT chk_browser_placements_observer_frame_rate_fps
  CHECK (
    (NOT requires_desktop AND observer_frame_rate_fps = 0)
    OR
    (requires_desktop AND observer_frame_rate_fps BETWEEN 1 AND 60)
  ) NOT VALID;

ALTER TABLE browser_placements
  VALIDATE CONSTRAINT chk_browser_placements_observer_frame_rate_fps;

COMMENT ON COLUMN browser_placements.observer_frame_rate_fps IS
  'Authoritative Node-acknowledged maximum Observer forwarding rate; zero is valid only when the Session has no desktop data plane';
