INSERT INTO runtime_builds(
    build_id,
    engine,
    version,
    platform,
    capabilities,
    resource_requirements,
    security_tier,
    signature,
    sbom_url,
    regression_status,
    validated_at,
    released_at
) VALUES (
    'runtime_local_chromium',
    'chromium',
    'local',
    'local-dev',
    '{"cdp":true,"stateCollector":true}'::jsonb,
    '{"resourceClass":"L1"}'::jsonb,
    'TIER_0',
    'local-development-signature',
    'urn:browsercloud:sbom:local',
    'STABLE',
    now(),
    now()
) ON CONFLICT (build_id) DO NOTHING;
