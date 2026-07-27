-- Backfill AUTO policy for Sessions created before V022.
-- Resource Class remains an internal scheduling implementation detail.

INSERT INTO session_resource_policies (
    session_id,
    tenant_id,
    mode,
    execution_environment,
    minimum_template,
    resolved_template,
    maximum_cpu_millis,
    maximum_memory_mib,
    scale_up_window_seconds,
    scale_down_window_seconds,
    adjustment_cooldown_seconds,
    allow_migration,
    allow_hibernate,
    block_migration_during_human_takeover,
    on_maximum_reached,
    status,
    status_reason,
    created_at,
    updated_at
)
SELECT
    session_record.id,
    session_record.tenant_id,
    'AUTO',
    CASE
        WHEN context.resource_class = 'L5' THEN 'NATIVE_OS'
        ELSE 'SYSTEM_MANAGED'
    END,
    'standard-v1',
    CASE context.resource_class
        WHEN 'L3' THEN 'interactive-v1'
        WHEN 'L4' THEN 'heavy-v1'
        WHEN 'L5' THEN 'native-standard-v1'
        ELSE 'standard-v1'
    END,
    4000,
    4096,
    60,
    1200,
    300,
    TRUE,
    TRUE,
    TRUE,
    'PAUSE_AGENT',
    'OBSERVING',
    'MIGRATED_POLICY_AWAITING_TELEMETRY',
    session_record.created_at,
    now()
FROM sessions session_record
JOIN LATERAL (
    SELECT candidate.resource_class
    FROM session_contexts candidate
    WHERE candidate.session_id = session_record.id
    ORDER BY candidate.context_epoch DESC
    LIMIT 1
) context ON TRUE
ON CONFLICT (session_id) DO NOTHING;
