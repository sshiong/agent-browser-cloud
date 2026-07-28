-- V030: Application-aware Business Recovery contracts and durable verdicts.
--
-- Contracts are tenant-owned, versioned and declarative. The Control Plane never
-- executes tenant supplied JavaScript or regular expressions while evaluating a
-- recovered Browser State.

CREATE TABLE application_recovery_contracts (
    contract_id                         TEXT PRIMARY KEY,
    tenant_id                           TEXT NOT NULL,
    application_id                      TEXT NOT NULL,
    version                             BIGINT NOT NULL CHECK (version > 0),
    expected_origins                    JSONB NOT NULL DEFAULT '[]',
    ready_route_prefixes                JSONB NOT NULL DEFAULT '[]',
    login_route_prefixes                JSONB NOT NULL DEFAULT '[]',
    required_targets                    JSONB NOT NULL DEFAULT '[]',
    login_targets                       JSONB NOT NULL DEFAULT '[]',
    permission_denied_targets           JSONB NOT NULL DEFAULT '[]',
    account_mismatch_targets            JSONB NOT NULL DEFAULT '[]',
    required_extension_ids              JSONB NOT NULL DEFAULT '[]',
    allow_depth_limited                 BOOLEAN NOT NULL DEFAULT FALSE,
    maximum_auto_recovery               INTEGER NOT NULL DEFAULT 0
                                            CHECK (maximum_auto_recovery BETWEEN 0 AND 10),
    enabled                             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, application_id),
    UNIQUE (contract_id, tenant_id, application_id),
    CONSTRAINT chk_application_recovery_contract_json_arrays CHECK (
        jsonb_typeof(expected_origins) = 'array'
        AND jsonb_typeof(ready_route_prefixes) = 'array'
        AND jsonb_typeof(login_route_prefixes) = 'array'
        AND jsonb_typeof(required_targets) = 'array'
        AND jsonb_typeof(login_targets) = 'array'
        AND jsonb_typeof(permission_denied_targets) = 'array'
        AND jsonb_typeof(account_mismatch_targets) = 'array'
        AND jsonb_typeof(required_extension_ids) = 'array'
    )
);

CREATE INDEX idx_application_recovery_contracts_tenant
ON application_recovery_contracts(tenant_id, application_id);

CREATE TABLE session_application_bindings (
    session_id                          TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id                           TEXT NOT NULL,
    application_id                      TEXT NOT NULL,
    contract_id                         TEXT NOT NULL,
    bound_at                            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, session_id),
    FOREIGN KEY (contract_id, tenant_id, application_id)
        REFERENCES application_recovery_contracts(contract_id, tenant_id, application_id)
);

CREATE INDEX idx_session_application_bindings_application
ON session_application_bindings(tenant_id, application_id);

CREATE TABLE business_recovery_validations (
    validation_id                       TEXT PRIMARY KEY,
    tenant_id                           TEXT NOT NULL,
    session_id                          TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    application_id                      TEXT,
    contract_id                         TEXT,
    contract_version                    BIGINT,
    context_epoch                       BIGINT NOT NULL,
    state_version                       BIGINT NOT NULL,
    verdict                             TEXT NOT NULL,
    ready                               BOOLEAN NOT NULL,
    evidence                            JSONB NOT NULL DEFAULT '[]',
    source                              TEXT NOT NULL,
    actor_id                            TEXT NOT NULL,
    request_id                          TEXT NOT NULL,
    evaluated_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_business_recovery_verdict CHECK (
        verdict IN (
            'READY',
            'READY_WITH_WARNING',
            'LOGIN_REQUIRED',
            'PERMISSION_CHANGED',
            'ACCOUNT_MISMATCH',
            'APPLICATION_UNAVAILABLE',
            'STATE_CHANGED',
            'MANUAL_RECOVERY_REQUIRED'
        )
    ),
    CONSTRAINT chk_business_recovery_source CHECK (
        source IN ('API', 'MIGRATION')
    ),
    CONSTRAINT chk_business_recovery_evidence_array CHECK (
        jsonb_typeof(evidence) = 'array'
    ),
    FOREIGN KEY (contract_id, tenant_id, application_id)
        REFERENCES application_recovery_contracts(contract_id, tenant_id, application_id)
);

CREATE INDEX idx_business_recovery_validations_latest
ON business_recovery_validations(session_id, evaluated_at DESC);

CREATE INDEX idx_business_recovery_validations_tenant
ON business_recovery_validations(tenant_id, evaluated_at DESC);

COMMENT ON TABLE application_recovery_contracts IS
    'Versioned tenant application Business Recovery contracts using a bounded declarative DSL';
COMMENT ON TABLE session_application_bindings IS
    'Immutable Session to tenant Application Recovery Contract binding';
COMMENT ON TABLE business_recovery_validations IS
    'Durable application-aware Business Recovery verdicts used by migration Ready Gate';
