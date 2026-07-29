-- V050: immutable Session contract-version binding and dual-control approval evidence.
--
-- Existing contracts deliberately remain unapproved. An upgrade must not invent a human
-- approval. Existing Session bindings are pinned to the version they used before this migration,
-- and all future bind/validation paths fail closed until that exact version is approved.

CREATE TABLE application_recovery_contract_approvals (
    approval_id                         TEXT PRIMARY KEY,
    tenant_id                           TEXT NOT NULL,
    contract_id                         TEXT NOT NULL,
    application_id                      TEXT NOT NULL,
    contract_version                    BIGINT NOT NULL CHECK (contract_version > 0),
    reason                              TEXT NOT NULL
        CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    state                               TEXT NOT NULL,
    requested_by                        TEXT NOT NULL,
    approved_by                         TEXT,
    rejected_by                         TEXT,
    requested_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at                          TIMESTAMPTZ,
    evidence_hash                       TEXT
        CHECK (evidence_hash IS NULL OR evidence_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_recovery_contract_approval_state CHECK (
        state IN ('REQUESTED', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT chk_recovery_contract_approval_decision CHECK (
        (state = 'REQUESTED'
            AND approved_by IS NULL
            AND rejected_by IS NULL
            AND decided_at IS NULL
            AND evidence_hash IS NULL)
        OR
        (state = 'APPROVED'
            AND approved_by IS NOT NULL
            AND approved_by <> requested_by
            AND rejected_by IS NULL
            AND decided_at IS NOT NULL
            AND evidence_hash IS NOT NULL)
        OR
        (state = 'REJECTED'
            AND approved_by IS NULL
            AND rejected_by IS NOT NULL
            AND decided_at IS NOT NULL
            AND evidence_hash IS NULL)
    ),
    FOREIGN KEY (contract_id, tenant_id, application_id)
        REFERENCES application_recovery_contracts(contract_id, tenant_id, application_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_recovery_contract_pending_approval
ON application_recovery_contract_approvals(contract_id, contract_version)
WHERE state = 'REQUESTED';

CREATE UNIQUE INDEX uq_recovery_contract_approved_version
ON application_recovery_contract_approvals(contract_id, contract_version)
WHERE state = 'APPROVED';

CREATE INDEX idx_recovery_contract_approvals_tenant
ON application_recovery_contract_approvals(tenant_id, requested_at DESC);

ALTER TABLE session_application_bindings
ADD COLUMN contract_version BIGINT;

UPDATE session_application_bindings binding
SET contract_version = contract.version
FROM application_recovery_contracts contract
WHERE contract.contract_id = binding.contract_id
  AND contract.tenant_id = binding.tenant_id
  AND contract.application_id = binding.application_id;

CREATE FUNCTION fill_session_application_contract_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.contract_version IS NULL THEN
        SELECT contract.version
        INTO NEW.contract_version
        FROM application_recovery_contracts contract
        WHERE contract.contract_id = NEW.contract_id
          AND contract.tenant_id = NEW.tenant_id
          AND contract.application_id = NEW.application_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_session_application_contract_version
BEFORE INSERT ON session_application_bindings
FOR EACH ROW
EXECUTE FUNCTION fill_session_application_contract_version();

ALTER TABLE session_application_bindings
ALTER COLUMN contract_version SET NOT NULL;

ALTER TABLE session_application_bindings
ADD CONSTRAINT chk_session_application_contract_version
CHECK (contract_version > 0) NOT VALID;

ALTER TABLE session_application_bindings
VALIDATE CONSTRAINT chk_session_application_contract_version;

COMMENT ON TABLE application_recovery_contract_approvals IS
    'Dual-control approval decisions bound to an exact immutable Application Recovery Contract version';
COMMENT ON COLUMN session_application_bindings.contract_version IS
    'Contract version authorized when the Session was created; later edits cannot silently change recovery policy';
