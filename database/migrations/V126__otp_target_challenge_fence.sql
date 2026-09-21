-- Historical OTP events were title-only and therefore stored no target. A real sensitive
-- one-time-code control now contributes a privacy-safe control category and can be bound to an
-- exact target/visual anchor. Keep both shapes valid for rolling N-1 readers and writers.
ALTER TABLE challenge_events
  ADD CONSTRAINT chk_challenge_event_target_v4
    CHECK (
      (suspected_type IN ('SINGLE_CLICK', 'OPAQUE_FRAME_SINGLE_CLICK')
        AND target_ref IS NOT NULL
        AND visual_anchor_hash ~ '^[a-f0-9]{64}$')
      OR
      (suspected_type = 'OTP' AND (
        (target_ref IS NULL AND visual_anchor_hash IS NULL)
        OR
        (target_ref IS NOT NULL AND visual_anchor_hash ~ '^[a-f0-9]{64}$')
      ))
      OR
      (suspected_type NOT IN ('SINGLE_CLICK', 'OPAQUE_FRAME_SINGLE_CLICK', 'OTP')
        AND target_ref IS NULL AND visual_anchor_hash IS NULL)
    ) NOT VALID;
ALTER TABLE challenge_events VALIDATE CONSTRAINT chk_challenge_event_target_v4;
ALTER TABLE challenge_events DROP CONSTRAINT chk_challenge_event_target;
ALTER TABLE challenge_events
  RENAME CONSTRAINT chk_challenge_event_target_v4 TO chk_challenge_event_target;
