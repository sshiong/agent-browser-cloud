-- Allow an exact DOM/A11y-bound single-click Challenge attempt to bypass screenshot/model
-- processing. Old rows and old writers remain VISION by default; old Nodes ignore the additive
-- command fields and reject the deliberately empty visual-action list before any input.

ALTER TABLE challenge_visual_jobs
  ALTER COLUMN capture_id DROP NOT NULL,
  ADD COLUMN execution_kind TEXT NOT NULL DEFAULT 'VISION';

ALTER TABLE challenge_visual_jobs
  ADD CONSTRAINT chk_challenge_visual_job_execution_kind
    CHECK (execution_kind IN ('VISION', 'STRUCTURAL_CLICK')) NOT VALID,
  ADD CONSTRAINT chk_challenge_visual_job_capture_requirement
    CHECK (
      (execution_kind = 'VISION' AND capture_id IS NOT NULL)
      OR
      (execution_kind = 'STRUCTURAL_CLICK' AND capture_id IS NULL AND evidence_id IS NULL)
    ) NOT VALID;

ALTER TABLE challenge_visual_jobs
  VALIDATE CONSTRAINT chk_challenge_visual_job_execution_kind;
ALTER TABLE challenge_visual_jobs
  VALIDATE CONSTRAINT chk_challenge_visual_job_capture_requirement;

COMMENT ON COLUMN challenge_visual_jobs.execution_kind IS
  'VISION requires governed screenshot/model processing; STRUCTURAL_CLICK uses an exact state/target/anchor-bound Node click without pixels or a model.';
