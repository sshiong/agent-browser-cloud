-- V052: trusted Provider/API evidence for Application-aware Business Recovery.
--
-- Browser DOM state cannot prove the current account, permission set, workspace or
-- business entity. Contract revisions therefore declare exact hash-based evidence
-- requirements, and a separately authenticated Application Adapter submits short-lived
-- attestations bound to the current Session context and Browser State version.

ALTER TABLE application_recovery_contracts
ADD COLUMN required_provider_evidence JSONB NOT NULL DEFAULT '[]';

ALTER TABLE application_recovery_contracts
ADD CONSTRAINT chk_application_recovery_contract_provider_evidence
CHECK (jsonb_typeof(required_provider_evidence) = 'array');

ALTER TABLE application_recovery_contract_revisions
ADD COLUMN required_provider_evidence JSONB NOT NULL DEFAULT '[]';

ALTER TABLE application_recovery_contract_revisions
ADD CONSTRAINT chk_application_recovery_revision_provider_evidence
CHECK (jsonb_typeof(required_provider_evidence) = 'array');

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

CREATE TABLE business_recovery_provider_evidence (
    evidence_id                         TEXT PRIMARY KEY,
    tenant_id                           TEXT NOT NULL,
    session_id                          TEXT NOT NULL
                                            REFERENCES sessions(id) ON DELETE CASCADE,
    application_id                      TEXT NOT NULL,
    contract_id                         TEXT NOT NULL,
    contract_version                    BIGINT NOT NULL CHECK (contract_version > 0),
    context_epoch                       BIGINT NOT NULL CHECK (context_epoch > 0),
    state_version                       BIGINT NOT NULL CHECK (state_version > 0),
    evidence_type                       TEXT NOT NULL,
    evidence_key                        TEXT NOT NULL,
    provider_id                         TEXT NOT NULL,
    expected_value_hash                 TEXT NOT NULL,
    observed_value_hash                 TEXT NOT NULL,
    outcome                             TEXT NOT NULL,
    provider_reference_hash             TEXT NOT NULL,
    adapter_actor_id                    TEXT NOT NULL,
    request_id                          TEXT NOT NULL,
    observed_at                         TIMESTAMPTZ NOT NULL,
    expires_at                          TIMESTAMPTZ NOT NULL,
    created_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_business_recovery_provider_type CHECK (
        evidence_type IN ('ACCOUNT', 'TENANT_WORKSPACE', 'PERMISSION', 'BUSINESS_ENTITY')
    ),
    CONSTRAINT chk_business_recovery_provider_outcome CHECK (
        outcome IN ('MATCH', 'MISMATCH', 'UNKNOWN')
    ),
    CONSTRAINT chk_business_recovery_provider_identifiers CHECK (
        evidence_key ~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'
        AND provider_id ~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'
        AND adapter_actor_id ~ '^[A-Za-z0-9_-]{1,128}$'
    ),
    CONSTRAINT chk_business_recovery_provider_hashes CHECK (
        expected_value_hash ~ '^[0-9a-f]{64}$'
        AND observed_value_hash ~ '^[0-9a-f]{64}$'
        AND provider_reference_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_business_recovery_provider_ttl CHECK (
        expires_at > observed_at
        AND expires_at <= observed_at + interval '15 minutes'
    ),
    FOREIGN KEY (contract_id, contract_version, tenant_id, application_id)
        REFERENCES application_recovery_contract_revisions(
            contract_id, contract_version, tenant_id, application_id
        )
);

CREATE INDEX idx_business_recovery_provider_evidence_lookup
ON business_recovery_provider_evidence(
    tenant_id,
    session_id,
    contract_id,
    contract_version,
    context_epoch,
    state_version,
    evidence_type,
    evidence_key,
    provider_id,
    observed_at DESC
);

CREATE INDEX idx_business_recovery_provider_evidence_expiry
ON business_recovery_provider_evidence(tenant_id, expires_at);

CREATE FUNCTION reject_business_recovery_provider_evidence_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Business Recovery Provider Evidence is immutable';
END;
$$;

CREATE TRIGGER trg_business_recovery_provider_evidence_immutable
BEFORE UPDATE ON business_recovery_provider_evidence
FOR EACH ROW
EXECUTE FUNCTION reject_business_recovery_provider_evidence_update();

COMMENT ON TABLE business_recovery_provider_evidence IS
    'Immutable trusted Adapter attestations used by the Business Recovery Ready Gate; deletion follows Session retention';
COMMENT ON COLUMN business_recovery_provider_evidence.provider_reference_hash IS
    'SHA-256 of the Provider-side evidence reference; raw tokens and identifiers are never stored';
