ALTER TABLE enterprise_service_level_events
    ADD COLUMN excluded_from_sla BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN exclusion_code TEXT;

CREATE TABLE enterprise_sla_exclusions (
    tenant_id                  TEXT NOT NULL,
    exclusion_code            TEXT NOT NULL,
    description               TEXT NOT NULL,
    enabled                   BOOLEAN NOT NULL,
    updated_by                TEXT NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, exclusion_code)
);

CREATE TABLE enterprise_retention_deletion_receipts (
    receipt_id                TEXT PRIMARY KEY,
    tenant_id                 TEXT NOT NULL,
    data_class                TEXT NOT NULL,
    object_id                 TEXT NOT NULL,
    content_digest            TEXT NOT NULL,
    policy_updated_at         TIMESTAMPTZ NOT NULL,
    receipt_hash              TEXT NOT NULL UNIQUE,
    deleted_by                TEXT NOT NULL,
    deleted_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_deletion_receipt_digest CHECK (
        content_digest ~ '^sha256:[a-f0-9]{64}$'
        AND receipt_hash ~ '^[a-f0-9]{64}$'
    )
);

CREATE INDEX idx_retention_receipt_tenant_time
ON enterprise_retention_deletion_receipts(tenant_id, deleted_at DESC);

CREATE TABLE enterprise_license_inventory (
    component_id              TEXT PRIMARY KEY,
    component_type            TEXT NOT NULL,
    component_name            TEXT NOT NULL,
    component_version         TEXT NOT NULL,
    license_id                TEXT NOT NULL,
    source_url                TEXT NOT NULL,
    approved                  BOOLEAN NOT NULL,
    evidence_hash             TEXT NOT NULL,
    updated_by                TEXT NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_license_component_type CHECK (
        component_type IN ('RUNTIME', 'EXTENSION', 'SERVICE', 'SDK')
    ),
    CONSTRAINT chk_license_evidence_hash CHECK (
        evidence_hash ~ '^[a-f0-9]{64}$'
    )
);

CREATE TABLE enterprise_audit_export_manifests (
    export_id                 TEXT PRIMARY KEY,
    tenant_id                 TEXT NOT NULL,
    from_sequence             BIGINT NOT NULL,
    to_sequence               BIGINT NOT NULL,
    event_count               BIGINT NOT NULL,
    first_event_hash          TEXT NOT NULL,
    last_event_hash           TEXT NOT NULL,
    manifest_hash             TEXT NOT NULL,
    signature_algorithm       TEXT NOT NULL,
    signing_key_id            TEXT NOT NULL,
    signature                 TEXT NOT NULL,
    generated_by              TEXT NOT NULL,
    generated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_audit_export_range CHECK (
        from_sequence > 0 AND to_sequence >= from_sequence AND event_count > 0
    ),
    CONSTRAINT chk_audit_export_hashes CHECK (
        first_event_hash ~ '^[a-f0-9]{64}$'
        AND last_event_hash ~ '^[a-f0-9]{64}$'
        AND manifest_hash ~ '^[a-f0-9]{64}$'
        AND signature ~ '^[a-f0-9]{64}$'
    )
);

INSERT INTO enterprise_license_inventory(
    component_id, component_type, component_name, component_version,
    license_id, source_url, approved, evidence_hash, updated_by, updated_at
) VALUES
    (
      'runtime_local_chromium', 'RUNTIME', 'Chromium Runtime', 'local',
      'BSD-3-Clause', 'https://www.chromium.org/Home/',
      TRUE, '425898f88d637ef39b342a0bf17103d7ce01b5080aab24390ec3c06faba527c0', 'migration', now()
    ),
    (
      'sdk_typescript', 'SDK', 'Agent Browser Cloud TypeScript SDK', '0.1.0',
      'Apache-2.0', 'repository://sdks/typescript',
      TRUE, '05cf72bf22f081a07af5c83c25379a9ae353d4155d88f9464faa726aa266877d', 'migration', now()
    ),
    (
      'sdk_python', 'SDK', 'Agent Browser Cloud Python SDK', '0.1.0',
      'Apache-2.0', 'repository://sdks/python',
      TRUE, '842b4d13eca138f769a8a9ee996962e030f9d53c65ad8456c362d633065b8f66', 'migration', now()
    ),
    (
      'sdk_go', 'SDK', 'Agent Browser Cloud Go SDK', '0.1.0',
      'Apache-2.0', 'repository://sdks/go',
      TRUE, 'f0e1c5f241a48e56fe5b7f02252599be6db436da1729f32e2591eff28478f82f', 'migration', now()
    ),
    (
      'sdk_java', 'SDK', 'Agent Browser Cloud Java SDK', '0.1.0',
      'Apache-2.0', 'repository://sdks/java',
      TRUE, '856a2cff804adbc4fee05035b6fa149c2c53c8c4e3dfd992a5030f9bdd628cfc', 'migration', now()
    )
ON CONFLICT (component_id) DO NOTHING;

COMMENT ON TABLE enterprise_retention_deletion_receipts IS
'Tamper-evident proof that an eligible retained object was deleted outside legal hold';
COMMENT ON TABLE enterprise_audit_export_manifests IS
'HMAC-SHA256 signed manifest over a contiguous tenant audit event range';
