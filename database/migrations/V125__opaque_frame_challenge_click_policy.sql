-- Explicit session opt-in for one bounded visual click inside an otherwise opaque frame.
-- The application also requires the frame host in the current Agent task allowedDomains,
-- an exact fresh frame boundary and a low-risk Challenge classification. Empty-by-default keeps
-- all existing sessions on Human Handoff.
ALTER TABLE sessions
  ADD COLUMN challenge_opaque_frame_click_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN challenge_opaque_frame_click_origins JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE sessions
  ADD CONSTRAINT chk_session_opaque_frame_click_origins
    CHECK (
      jsonb_typeof(challenge_opaque_frame_click_origins) = 'array'
      AND jsonb_array_length(challenge_opaque_frame_click_origins) <= 16
    );

-- V087's closed enum constraint must be replaced before the new event can be written. Build and
-- validate the superset first so rolling N-1 writers retain all of their accepted values.
ALTER TABLE challenge_events
  ADD CONSTRAINT chk_challenge_event_type_v2
    CHECK (suspected_type IN (
      'SINGLE_CLICK', 'OPAQUE_FRAME_SINGLE_CLICK', 'IMAGE_SELECTION', 'PUZZLE', 'OTP',
      'DEVICE_CONFIRMATION', 'MULTI_ROUND', 'USER_JUDGMENT', 'PAYMENT_CONFIRMATION', 'UNKNOWN'
    )) NOT VALID;
ALTER TABLE challenge_events VALIDATE CONSTRAINT chk_challenge_event_type_v2;
ALTER TABLE challenge_events DROP CONSTRAINT chk_challenge_event_type;
ALTER TABLE challenge_events
  RENAME CONSTRAINT chk_challenge_event_type_v2 TO chk_challenge_event_type;

ALTER TABLE challenge_events
  ADD CONSTRAINT chk_challenge_event_target_v3
    CHECK (
      (suspected_type IN ('SINGLE_CLICK', 'OPAQUE_FRAME_SINGLE_CLICK')
        AND target_ref IS NOT NULL
        AND visual_anchor_hash ~ '^[a-f0-9]{64}$')
      OR
      (suspected_type = 'OTP' AND visual_anchor_hash IS NULL)
      OR
      (suspected_type NOT IN ('SINGLE_CLICK', 'OPAQUE_FRAME_SINGLE_CLICK', 'OTP')
        AND target_ref IS NULL AND visual_anchor_hash IS NULL)
    ) NOT VALID;
ALTER TABLE challenge_events VALIDATE CONSTRAINT chk_challenge_event_target_v3;
ALTER TABLE challenge_events DROP CONSTRAINT chk_challenge_event_target;
ALTER TABLE challenge_events
  RENAME CONSTRAINT chk_challenge_event_target_v3 TO chk_challenge_event_target;

COMMENT ON COLUMN sessions.challenge_opaque_frame_click_origins IS
  'Exact origin-only allowlist for one low-risk Challenge click; never authorizes text, secrets, slides, payments or account decisions.';
