-- Trusted Extension recovery is intentionally narrower than generic browser recovery.
--
-- A contract must name one real Chromium Extension ID (32 characters in the a-p alphabet).
-- The Control Plane verifies that the ID is part of the current Placement before dispatch;
-- the Browser Node executes chrome.runtime.reload() only inside the matching trusted
-- chrome-extension:// execution context.

ALTER TABLE application_recovery_contracts
  ADD COLUMN recovery_extension_id TEXT;

ALTER TABLE application_recovery_contracts
  DROP CONSTRAINT chk_application_recovery_action;

ALTER TABLE application_recovery_contracts
  ADD CONSTRAINT chk_application_recovery_action
    CHECK (recovery_action IN (
      'NONE',
      'RELOAD',
      'NAVIGATE_HOME',
      'REOPEN_KNOWN_ROUTE',
      'REFRESH_SESSION',
      'RESTART_EXTENSION'
    )) NOT VALID;

ALTER TABLE application_recovery_contracts
  VALIDATE CONSTRAINT chk_application_recovery_action;

ALTER TABLE application_recovery_contracts
  ADD CONSTRAINT chk_application_recovery_extension_target
    CHECK (
      (
        recovery_action = 'RESTART_EXTENSION'
        AND recovery_extension_id IS NOT NULL
        AND recovery_extension_id ~ '^[a-p]{32}$'
        AND required_extension_ids ? recovery_extension_id
      )
      OR
      (
        recovery_action <> 'RESTART_EXTENSION'
        AND recovery_extension_id IS NULL
      )
    ) NOT VALID;

ALTER TABLE application_recovery_contracts
  VALIDATE CONSTRAINT chk_application_recovery_extension_target;

ALTER TABLE business_recovery_actions
  ADD COLUMN target_extension_id TEXT;

ALTER TABLE business_recovery_actions
  DROP CONSTRAINT chk_business_recovery_action_type;

ALTER TABLE business_recovery_actions
  ADD CONSTRAINT chk_business_recovery_action_type
    CHECK (action_type IN (
      'RELOAD',
      'NAVIGATE_HOME',
      'REOPEN_KNOWN_ROUTE',
      'REFRESH_SESSION',
      'RESTART_EXTENSION'
    )) NOT VALID;

ALTER TABLE business_recovery_actions
  VALIDATE CONSTRAINT chk_business_recovery_action_type;

ALTER TABLE business_recovery_actions
  DROP CONSTRAINT chk_business_recovery_action_target;

ALTER TABLE business_recovery_actions
  ADD CONSTRAINT chk_business_recovery_action_target
    CHECK (
      (
        action_type IN ('RELOAD', 'REFRESH_SESSION')
        AND target_url IS NULL
        AND target_extension_id IS NULL
      )
      OR
      (
        action_type IN ('NAVIGATE_HOME', 'REOPEN_KNOWN_ROUTE')
        AND target_url ~ '^https?://'
        AND target_extension_id IS NULL
      )
      OR
      (
        action_type = 'RESTART_EXTENSION'
        AND target_url IS NULL
        AND target_extension_id IS NOT NULL
        AND target_extension_id ~ '^[a-p]{32}$'
      )
    ) NOT VALID;

ALTER TABLE business_recovery_actions
  VALIDATE CONSTRAINT chk_business_recovery_action_target;

COMMENT ON COLUMN application_recovery_contracts.recovery_extension_id IS
  'Contract-owned Chromium Extension ID eligible for trusted RESTART_EXTENSION';

COMMENT ON COLUMN business_recovery_actions.target_extension_id IS
  'Immutable Chromium Extension ID sent to the Browser Node for this recovery attempt';
