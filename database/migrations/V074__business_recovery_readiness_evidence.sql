-- V074: real document/network readiness evidence and transient UI blockers.
--
-- Existing contracts remain byte-for-byte compatible: all new rules default disabled. New
-- Browser Nodes can provide continuous CDP Network evidence; a contract that opts in fails
-- closed when an N-1 Node leaves the additive fields empty.

ALTER TABLE application_recovery_contracts
ADD COLUMN require_document_complete BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN minimum_network_quiet_millis INTEGER NOT NULL DEFAULT 0
    CHECK (minimum_network_quiet_millis BETWEEN 0 AND 30000),
ADD COLUMN transient_blocker_targets JSONB NOT NULL DEFAULT '[]'
    CHECK (jsonb_typeof(transient_blocker_targets) = 'array');

ALTER TABLE application_recovery_contract_revisions
ADD COLUMN require_document_complete BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN minimum_network_quiet_millis INTEGER NOT NULL DEFAULT 0
    CHECK (minimum_network_quiet_millis BETWEEN 0 AND 30000),
ADD COLUMN transient_blocker_targets JSONB NOT NULL DEFAULT '[]'
    CHECK (jsonb_typeof(transient_blocker_targets) = 'array');

CREATE OR REPLACE FUNCTION snapshot_application_recovery_contract_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO application_recovery_contract_revisions (
        contract_id,
        contract_version,
        tenant_id,
        application_id,
        expected_origins,
        ready_route_prefixes,
        login_route_prefixes,
        required_targets,
        login_targets,
        permission_denied_targets,
        account_mismatch_targets,
        required_extension_ids,
        required_provider_evidence,
        require_document_complete,
        minimum_network_quiet_millis,
        transient_blocker_targets,
        allow_depth_limited,
        recovery_action,
        recovery_extension_id,
        maximum_auto_recovery,
        enabled,
        contract_created_at,
        published_at
    ) VALUES (
        NEW.contract_id,
        NEW.version,
        NEW.tenant_id,
        NEW.application_id,
        NEW.expected_origins,
        NEW.ready_route_prefixes,
        NEW.login_route_prefixes,
        NEW.required_targets,
        NEW.login_targets,
        NEW.permission_denied_targets,
        NEW.account_mismatch_targets,
        NEW.required_extension_ids,
        NEW.required_provider_evidence,
        NEW.require_document_complete,
        NEW.minimum_network_quiet_millis,
        NEW.transient_blocker_targets,
        NEW.allow_depth_limited,
        NEW.recovery_action,
        NEW.recovery_extension_id,
        NEW.maximum_auto_recovery,
        NEW.enabled,
        NEW.created_at,
        NEW.updated_at
    )
    ON CONFLICT (contract_id, contract_version) DO NOTHING;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN application_recovery_contracts.minimum_network_quiet_millis IS
    'Required continuous CDP Network quiet window; zero disables the rule';
COMMENT ON COLUMN application_recovery_contracts.transient_blocker_targets IS
    'Exact visible ARIA role/name indicators for blocking dialogs, alerts and status toasts';
