ALTER TABLE browser_nodes
    ADD COLUMN certified_media_slots INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reserved_media_slots INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN supports_media BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_browser_node_media_capacity CHECK (
        certified_media_slots >= 0
        AND reserved_media_slots BETWEEN 0 AND certified_media_slots
    );

ALTER TABLE session_resource_demands
    ADD COLUMN media_workload BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN requested_media_streams INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN media_bitrate_kbps INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_session_media_demand CHECK (
        requested_media_streams BETWEEN 0 AND 32
        AND media_bitrate_kbps BETWEEN 0 AND 1000000
        AND (
            media_workload
            OR (requested_media_streams = 0 AND media_bitrate_kbps = 0)
        )
    );

ALTER TABLE browser_placements
    ADD COLUMN requires_media BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN media_slots INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN media_bitrate_kbps INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_browser_placement_media CHECK (
        media_slots BETWEEN 0 AND 32
        AND media_bitrate_kbps BETWEEN 0 AND 1000000
        AND (
            requires_media
            OR (media_slots = 0 AND media_bitrate_kbps = 0)
        )
    );

CREATE TABLE tenant_media_quotas (
    tenant_id                   TEXT PRIMARY KEY,
    max_concurrent_streams      INTEGER NOT NULL,
    max_bitrate_kbps            INTEGER NOT NULL,
    updated_by                  TEXT NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_tenant_media_quota CHECK (
        max_concurrent_streams BETWEEN 0 AND 10000
        AND max_bitrate_kbps BETWEEN 0 AND 100000000
    )
);

ALTER TABLE extension_profiles
    ADD COLUMN sampling_tier TEXT NOT NULL DEFAULT 'HIGH',
    ADD COLUMN healthy_sample_streak INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_sample_at TIMESTAMPTZ,
    ADD COLUMN sampling_cpu_budget_millis INTEGER NOT NULL DEFAULT 25,
    ADD CONSTRAINT chk_extension_sampling_tier CHECK (
        sampling_tier IN ('LOW', 'MEDIUM', 'HIGH', 'DEEP')
    ),
    ADD CONSTRAINT chk_extension_sampling_budget CHECK (
        sampling_cpu_budget_millis BETWEEN 1 AND 1000
    );

CREATE TABLE extension_profile_samples (
    sample_id                   TEXT PRIMARY KEY,
    extension_id                TEXT NOT NULL REFERENCES extension_profiles(extension_id),
    node_id                     TEXT NOT NULL REFERENCES browser_nodes(node_id),
    cpu_millis                  INTEGER NOT NULL,
    memory_mib                 INTEGER NOT NULL,
    cgroup_psi_burst            BOOLEAN NOT NULL,
    sample_cpu_millis           INTEGER NOT NULL,
    observed_at                 TIMESTAMPTZ NOT NULL,
    recorded_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_extension_sample_resources CHECK (
        cpu_millis BETWEEN 0 AND 100000
        AND memory_mib BETWEEN 0 AND 1048576
        AND sample_cpu_millis BETWEEN 0 AND 1000
    )
);

CREATE INDEX idx_extension_profile_samples_window
ON extension_profile_samples(extension_id, observed_at DESC);

INSERT INTO tenant_media_quotas(
    tenant_id, max_concurrent_streams, max_bitrate_kbps, updated_by, updated_at
) VALUES ('tenant-local', 4, 20000, 'migration', now())
ON CONFLICT (tenant_id) DO NOTHING;

COMMENT ON TABLE tenant_media_quotas IS
'Independent tenant encoder-stream and aggregate bitrate admission limits';
COMMENT ON TABLE extension_profile_samples IS
'Bounded adaptive Extension observations used for P95 weighting and sampling tiers';
