-- V098: approved Application Recovery Contract routes become Browser transaction Site Policy.
--
-- The policy remains part of the immutable, exact-version Recovery Contract snapshot. Runtime
-- commands contain only normalized path prefixes, version and a SHA-256 integrity hash; URLs,
-- query strings, request bodies and headers never cross the Browser Node boundary.

ALTER TABLE application_recovery_contracts
ADD COLUMN payment_security_route_prefixes JSONB NOT NULL DEFAULT '[]',
ADD COLUMN critical_transaction_route_prefixes JSONB NOT NULL DEFAULT '[]';

ALTER TABLE application_recovery_contract_revisions
ADD COLUMN payment_security_route_prefixes JSONB NOT NULL DEFAULT '[]',
ADD COLUMN critical_transaction_route_prefixes JSONB NOT NULL DEFAULT '[]';

ALTER TABLE application_recovery_contracts
ADD CONSTRAINT chk_application_recovery_contract_transaction_routes
CHECK (
    jsonb_typeof(payment_security_route_prefixes) = 'array'
    AND jsonb_typeof(critical_transaction_route_prefixes) = 'array'
) NOT VALID;

ALTER TABLE application_recovery_contract_revisions
ADD CONSTRAINT chk_application_recovery_revision_transaction_routes
CHECK (
    jsonb_typeof(payment_security_route_prefixes) = 'array'
    AND jsonb_typeof(critical_transaction_route_prefixes) = 'array'
) NOT VALID;

ALTER TABLE application_recovery_contracts
VALIDATE CONSTRAINT chk_application_recovery_contract_transaction_routes;

ALTER TABLE application_recovery_contract_revisions
VALIDATE CONSTRAINT chk_application_recovery_revision_transaction_routes;

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
        payment_security_route_prefixes,
        critical_transaction_route_prefixes,
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
        NEW.payment_security_route_prefixes,
        NEW.critical_transaction_route_prefixes,
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

COMMENT ON COLUMN application_recovery_contracts.payment_security_route_prefixes IS
    'Approved URL-path prefixes that extend Browser-side payment/account-security Safe Point detection';
COMMENT ON COLUMN application_recovery_contracts.critical_transaction_route_prefixes IS
    'Approved URL-path prefixes that extend Browser-side critical transaction Safe Point detection';
