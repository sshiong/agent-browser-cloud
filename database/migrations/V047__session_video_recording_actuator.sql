ALTER TABLE session_resource_demands
  ADD COLUMN video_recording_requested BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE browser_placements
  ADD COLUMN video_recording_requested BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN video_recording_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE browser_placements
  ADD CONSTRAINT chk_browser_placements_video_recording_state
  CHECK (NOT video_recording_enabled OR video_recording_requested) NOT VALID;

ALTER TABLE browser_placements
  VALIDATE CONSTRAINT chk_browser_placements_video_recording_state;

COMMENT ON COLUMN session_resource_demands.video_recording_requested IS
  'Immutable create-time request for independent CDP pixel recording';
COMMENT ON COLUMN browser_placements.video_recording_requested IS
  'Placement copy of the requested recording capability';
COMMENT ON COLUMN browser_placements.video_recording_enabled IS
  'Authoritative Node-acknowledged current recording actuator state';
