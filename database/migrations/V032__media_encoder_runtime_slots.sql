ALTER TABLE browser_placements
  ADD COLUMN media_encoder_slots INTEGER NOT NULL DEFAULT 0;

UPDATE browser_placements
SET media_encoder_slots = media_slots
WHERE requires_media
  AND media_slots > 0;

ALTER TABLE browser_placements
  ADD CONSTRAINT chk_browser_placements_media_encoder_slots
    CHECK (
      (NOT requires_media AND media_encoder_slots = 0)
      OR
      (requires_media AND media_encoder_slots BETWEEN 1 AND media_slots)
    ) NOT VALID;

ALTER TABLE browser_placements
  VALIDATE CONSTRAINT chk_browser_placements_media_encoder_slots;
