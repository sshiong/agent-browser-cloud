-- 仅用于本地开发。不得装载真实客户数据或凭据。
INSERT INTO profiles (
    profile_id,
    tenant_id,
    name,
    description,
    storage_path,
    state
) VALUES (
    'profile_local_001',
    'tenant-local',
    'Local development profile',
    'Non-production seed profile',
    '/var/lib/browsercloud/profiles/profile_local_001',
    'ACTIVE'
) ON CONFLICT (profile_id) DO NOTHING;

INSERT INTO runtime_builds (
    build_id,
    engine,
    version,
    platform,
    security_tier,
    regression_status
) VALUES (
    'runtime_local_chromium',
    'chromium',
    'local',
    'linux-arm64-or-amd64',
    'TIER_0',
    'DEV_ONLY'
) ON CONFLICT (build_id) DO NOTHING;
