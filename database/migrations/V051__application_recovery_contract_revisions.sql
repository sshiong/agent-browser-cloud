-- V051: immutable Application Recovery Contract revisions and explicit Session rebind Operations.
--
-- The mutable application_recovery_contracts row remains the current drafting/publishing head.
-- Every published head is copied into an append-only revision row. Session evaluation always reads
-- its exact bound revision, while the current head's enabled flag remains the tenant kill switch.

CREATE TABLE application_recovery_contract_revisions (
    contract_id                         TEXT NOT NULL,
    contract_version                    BIGINT NOT NULL CHECK (contract_version > 0),
    tenant_id                           TEXT NOT NULL,
    application_id                      TEXT NOT NULL,
    expected_origins                    JSONB NOT NULL DEFAULT '[]',
    ready_route_prefixes                JSONB NOT NULL DEFAULT '[]',
    login_route_prefixes                JSONB NOT NULL DEFAULT '[]',
    required_targets                    JSONB NOT NULL DEFAULT '[]',
    login_targets                       JSONB NOT NULL DEFAULT '[]',
    permission_denied_targets           JSONB NOT NULL DEFAULT '[]',
    account_mismatch_targets            JSONB NOT NULL DEFAULT '[]',
    required_extension_ids              JSONB NOT NULL DEFAULT '[]',
    allow_depth_limited                 BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_action                     TEXT NOT NULL DEFAULT 'NONE',
    recovery_extension_id               TEXT,
    maximum_auto_recovery               INTEGER NOT NULL DEFAULT 0
                                            CHECK (maximum_auto_recovery BETWEEN 0 AND 10),
    enabled                             BOOLEAN NOT NULL DEFAULT TRUE,
    contract_created_at                 TIMESTAMPTZ NOT NULL,
    published_at                        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (contract_id, contract_version),
    UNIQUE (contract_id, contract_version, tenant_id, application_id),
    CONSTRAINT chk_application_recovery_revision_json_arrays CHECK (
        jsonb_typeof(expected_origins) = 'array'
        AND jsonb_typeof(ready_route_prefixes) = 'array'
        AND jsonb_typeof(login_route_prefixes) = 'array'
        AND jsonb_typeof(required_targets) = 'array'
        AND jsonb_typeof(login_targets) = 'array'
        AND jsonb_typeof(permission_denied_targets) = 'array'
        AND jsonb_typeof(account_mismatch_targets) = 'array'
        AND jsonb_typeof(required_extension_ids) = 'array'
    ),
    CONSTRAINT chk_application_recovery_revision_action CHECK (
        recovery_action IN (
            'NONE',
            'RELOAD',
            'NAVIGATE_HOME',
            'REOPEN_KNOWN_ROUTE',
            'REFRESH_SESSION',
            'RESTART_EXTENSION'
        )
    )
);

CREATE INDEX idx_application_recovery_revisions_tenant
ON application_recovery_contract_revisions(tenant_id, application_id, contract_version DESC);

CREATE FUNCTION snapshot_application_recovery_contract_revision()
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
    allow_depth_limited,
    recovery_action,
    recovery_extension_id,
    maximum_auto_recovery,
    enabled,
    contract_created_at,
    published_at
)
SELECT
    contract_id,
    version,
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
    allow_depth_limited,
    recovery_action,
    recovery_extension_id,
    maximum_auto_recovery,
    enabled,
    created_at,
    updated_at
FROM application_recovery_contracts
ON CONFLICT (contract_id, contract_version) DO NOTHING;

CREATE TRIGGER trg_application_recovery_contract_revision
AFTER INSERT OR UPDATE ON application_recovery_contracts
FOR EACH ROW
EXECUTE FUNCTION snapshot_application_recovery_contract_revision();

CREATE FUNCTION reject_application_recovery_revision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Application Recovery Contract revisions are immutable';
END;
$$;

CREATE TRIGGER trg_application_recovery_revision_immutable
BEFORE UPDATE OR DELETE ON application_recovery_contract_revisions
FOR EACH ROW
EXECUTE FUNCTION reject_application_recovery_revision_mutation();

ALTER TABLE session_application_bindings
ADD CONSTRAINT fk_session_application_binding_revision
FOREIGN KEY (contract_id, contract_version, tenant_id, application_id)
REFERENCES application_recovery_contract_revisions(
    contract_id, contract_version, tenant_id, application_id
) NOT VALID;

ALTER TABLE application_recovery_contract_approvals
ADD CONSTRAINT fk_recovery_contract_approval_revision
FOREIGN KEY (contract_id, contract_version, tenant_id, application_id)
REFERENCES application_recovery_contract_revisions(
    contract_id, contract_version, tenant_id, application_id
) NOT VALID;

CREATE TABLE session_application_rebind_operations (
    operation_id                        TEXT PRIMARY KEY
                                            REFERENCES exclusive_operations(operation_id),
    tenant_id                           TEXT NOT NULL,
    session_id                          TEXT NOT NULL
                                            REFERENCES sessions(id) ON DELETE CASCADE,
    application_id                      TEXT NOT NULL,
    contract_id                         TEXT NOT NULL,
    previous_contract_version           BIGINT NOT NULL
                                            CHECK (previous_contract_version > 0),
    target_contract_version             BIGINT NOT NULL
                                            CHECK (target_contract_version > 0),
    actor_id                            TEXT NOT NULL,
    request_id                          TEXT NOT NULL,
    created_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at                        TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (contract_id, target_contract_version, tenant_id, application_id)
        REFERENCES application_recovery_contract_revisions(
            contract_id, contract_version, tenant_id, application_id
        )
);

CREATE INDEX idx_session_application_rebind_history
ON session_application_rebind_operations(session_id, created_at DESC);

COMMENT ON TABLE application_recovery_contract_revisions IS
    'Append-only exact-version recovery policy snapshots used by bound Sessions';
COMMENT ON TABLE session_application_rebind_operations IS
    'Idempotent, committed Operations that explicitly upgrade a Session contract binding';
COMMENT ON CONSTRAINT fk_session_application_binding_revision
ON session_application_bindings IS
    'NOT VALID preserves pre-V051 bindings whose historical body cannot be reconstructed; all new writes are checked';
