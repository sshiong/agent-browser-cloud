-- Additive, bounded human-like motion policy for low-risk visual Challenge automation.
-- Defaults preserve the v103 behavior envelope while avoiding fixed straight-line automation.

ALTER TABLE sessions
  ADD COLUMN challenge_motion_min_steps SMALLINT NOT NULL DEFAULT 8,
  ADD COLUMN challenge_motion_max_steps SMALLINT NOT NULL DEFAULT 18,
  ADD COLUMN challenge_motion_min_delay_ms SMALLINT NOT NULL DEFAULT 12,
  ADD COLUMN challenge_motion_max_delay_ms SMALLINT NOT NULL DEFAULT 45,
  ADD COLUMN challenge_target_offset_ratio DOUBLE PRECISION NOT NULL DEFAULT 0.15;

ALTER TABLE sessions
  ADD CONSTRAINT chk_sessions_challenge_motion_steps
    CHECK (
      challenge_motion_min_steps BETWEEN 4 AND 32
      AND challenge_motion_max_steps BETWEEN challenge_motion_min_steps AND 40
    ) NOT VALID,
  ADD CONSTRAINT chk_sessions_challenge_motion_delay
    CHECK (
      challenge_motion_min_delay_ms BETWEEN 5 AND 100
      AND challenge_motion_max_delay_ms BETWEEN challenge_motion_min_delay_ms AND 150
    ) NOT VALID,
  ADD CONSTRAINT chk_sessions_challenge_target_offset
    CHECK (challenge_target_offset_ratio BETWEEN 0 AND 0.35) NOT VALID;

ALTER TABLE sessions VALIDATE CONSTRAINT chk_sessions_challenge_motion_steps;
ALTER TABLE sessions VALIDATE CONSTRAINT chk_sessions_challenge_motion_delay;
ALTER TABLE sessions VALIDATE CONSTRAINT chk_sessions_challenge_target_offset;

ALTER TABLE challenge_automation_runs
  ADD COLUMN motion_min_steps SMALLINT NOT NULL DEFAULT 8,
  ADD COLUMN motion_max_steps SMALLINT NOT NULL DEFAULT 18,
  ADD COLUMN motion_min_delay_ms SMALLINT NOT NULL DEFAULT 12,
  ADD COLUMN motion_max_delay_ms SMALLINT NOT NULL DEFAULT 45,
  ADD COLUMN target_offset_ratio DOUBLE PRECISION NOT NULL DEFAULT 0.15;

ALTER TABLE challenge_automation_runs
  ADD CONSTRAINT chk_challenge_run_motion_steps
    CHECK (
      motion_min_steps BETWEEN 4 AND 32
      AND motion_max_steps BETWEEN motion_min_steps AND 40
    ) NOT VALID,
  ADD CONSTRAINT chk_challenge_run_motion_delay
    CHECK (
      motion_min_delay_ms BETWEEN 5 AND 100
      AND motion_max_delay_ms BETWEEN motion_min_delay_ms AND 150
    ) NOT VALID,
  ADD CONSTRAINT chk_challenge_run_target_offset
    CHECK (target_offset_ratio BETWEEN 0 AND 0.35) NOT VALID;

ALTER TABLE challenge_automation_runs VALIDATE CONSTRAINT chk_challenge_run_motion_steps;
ALTER TABLE challenge_automation_runs VALIDATE CONSTRAINT chk_challenge_run_motion_delay;
ALTER TABLE challenge_automation_runs VALIDATE CONSTRAINT chk_challenge_run_target_offset;

COMMENT ON COLUMN sessions.challenge_motion_min_steps IS
  'Minimum bounded Bezier pointer samples for low-risk visual Challenge automation';
COMMENT ON COLUMN sessions.challenge_target_offset_ratio IS
  'Maximum bounded target jitter ratio; visual actions remain viewport and policy constrained';
