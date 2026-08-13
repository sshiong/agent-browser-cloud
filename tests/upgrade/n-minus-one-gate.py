#!/usr/bin/env python3
"""Fail the release when additive N/N-1 compatibility invariants regress."""

import hashlib
import json
import pathlib
import re


ROOT = pathlib.Path(__file__).resolve().parents[2]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


recording_capacity_service = read(
    "apps/control-plane/src/main/java/io/browsercloud/application/BrowserCapacityApplicationService.java"
)
recording_node_agent = read("apps/browser-node/crates/node-agent/src/main.rs")
recording_session_recorder = read(
    "apps/browser-node/crates/session-recorder/src/lib.rs"
)
for invariant in (
    'RECORDING_REDACTION_CAPABILITY = "recordingRedaction"',
    'RECORDING_REDACTION_CAPABILITY_VERSION = "frame-mask-v1"',
    "requiresRecordingRedaction = demand.isVideoRecordingRequested()",
    '"NO_RECORDING_REDACTION_CAPABLE_NODE"',
):
    assert invariant in recording_capacity_service, (
        f"recording placement lacks rolling redaction capability gate: {invariant}"
    )
for invariant in ('"recordingRedaction".to_owned()', '"frame-mask-v1"'):
    assert invariant in recording_node_agent, (
        f"Browser Node lacks recording redaction capability advertisement: {invariant}"
    )
for invariant in (
    "RECORDING_REDACTION_POLICY_VERSION: u32 = 1",
    "recording capture failed before a redaction-safe completion marker",
    "storage helper did not acknowledge the recording redaction manifest",
):
    assert invariant in recording_session_recorder, (
        f"Session Recorder lacks fail-closed redaction invariant: {invariant}"
    )


enterprise_migrations = {
    version: read(f"database/migrations/{version}")
    for version in (
        "V019__enterprise_operations.sql",
        "V020__media_and_extension_sampling.sql",
        "V021__enterprise_governance_evidence.sql",
    )
}

remote_desktop_workspace_quota_migration = read(
    "database/migrations/V093__workspace_remote_desktop_actor_quotas.sql"
)
remote_desktop_workspace_quota_upper = remote_desktop_workspace_quota_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in remote_desktop_workspace_quota_upper, (
        f"Workspace desktop quota migration contains incompatible operation: {forbidden}"
    )
for column, default in (
    ("REMOTE_DESKTOP_CONTROL_BITRATE_LIMIT_KBPS", "8000"),
    ("REMOTE_DESKTOP_CONTROL_FRAME_RATE_LIMIT_FPS", "30"),
    ("REMOTE_DESKTOP_VIEWER_BITRATE_LIMIT_KBPS", "4000"),
    ("REMOTE_DESKTOP_VIEWER_FRAME_RATE_LIMIT_FPS", "15"),
):
    declaration = re.search(
        rf"ADD COLUMN\s+{column}\s+[^,;]+",
        remote_desktop_workspace_quota_migration,
        flags=re.IGNORECASE,
    )
    assert declaration, f"missing additive Workspace desktop quota {column}"
    upper_declaration = declaration.group(0).upper()
    assert "NOT NULL" in upper_declaration
    assert f"DEFAULT {default}" in upper_declaration
for constraint in (
    "CHK_WORKSPACE_REMOTE_DESKTOP_CONTROL_BITRATE",
    "CHK_WORKSPACE_REMOTE_DESKTOP_CONTROL_FPS",
    "CHK_WORKSPACE_REMOTE_DESKTOP_VIEWER_BITRATE",
    "CHK_WORKSPACE_REMOTE_DESKTOP_VIEWER_FPS",
):
    assert f"CONSTRAINT {constraint}" in remote_desktop_workspace_quota_upper
    assert f"VALIDATE CONSTRAINT {constraint}" in remote_desktop_workspace_quota_upper

remote_desktop_usage_migration = read(
    "database/migrations/V094__remote_desktop_usage_metering.sql"
)
remote_desktop_usage_upper = remote_desktop_usage_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in remote_desktop_usage_upper, (
        f"remote desktop usage migration contains incompatible operation: {forbidden}"
    )
for declaration in (
    "ADD COLUMN REMOTE_DESKTOP_EGRESS_GIB_USD NUMERIC(12,6) NOT NULL DEFAULT 0",
    "ADD COLUMN FORWARDED_BYTES BIGINT NOT NULL DEFAULT 0",
    "ADD COLUMN QUOTA_WAIT_MILLIS BIGINT NOT NULL DEFAULT 0",
    "ADD COLUMN THROTTLED_BATCHES BIGINT NOT NULL DEFAULT 0",
    "ADD COLUMN EGRESS_COST_USD NUMERIC(18,9) NOT NULL DEFAULT 0",
    "ADD COLUMN UNPRICED_FORWARDED_BYTES BIGINT NOT NULL DEFAULT 0",
):
    assert declaration in remote_desktop_usage_upper, (
        f"remote desktop usage migration lacks rolling default: {declaration}"
    )
for constraint in (
    "CHK_ENTERPRISE_COST_REMOTE_DESKTOP_EGRESS",
    "CHK_REMOTE_DESKTOP_PARTICIPANT_USAGE",
    "CHK_REMOTE_DESKTOP_PARTICIPANT_USAGE_PRICING",
):
    assert f"CONSTRAINT {constraint}" in remote_desktop_usage_upper
    assert f"VALIDATE CONSTRAINT {constraint}" in remote_desktop_usage_upper
for invariant in (
    "CREATE TABLE REMOTE_DESKTOP_USAGE_LEDGER",
    "EVENT_ID             TEXT PRIMARY KEY",
    "CREATE INDEX IDX_REMOTE_DESKTOP_USAGE_LEDGER_SESSION",
    "CREATE INDEX IDX_REMOTE_DESKTOP_USAGE_LEDGER_ACTOR",
):
    assert invariant in remote_desktop_usage_upper, (
        f"remote desktop usage migration lacks invariant: {invariant}"
    )

profile_export_migration = read(
    "database/migrations/V095__profile_export_access_governance.sql"
)
profile_export_upper = profile_export_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in profile_export_upper, (
        f"Profile export migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE PROFILE_EXPORT_ACCESS_GRANTS",
    "UNIQUE (TENANT_ID, ACTOR_ID, IDEMPOTENCY_KEY)",
    "CREATE TRIGGER TRG_PROFILE_EXPORT_ACCESS_SCOPE",
    "STATE IN ('ISSUED', 'REDEEMING', 'REDEEMED', 'FAILED')",
    "EXPIRES_AT <= CREATED_AT + INTERVAL '5 MINUTES'",
    "VALIDATE CONSTRAINT CHK_PROFILE_EXPORT_ACCESS_RESULT",
):
    assert invariant in profile_export_upper, (
        f"Profile export migration lacks invariant: {invariant}"
    )

warm_tier_migration = read(
    "database/migrations/V096__profile_warm_tier_delta_journal.sql"
)
warm_tier_upper = warm_tier_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER COLUMN"):
    assert forbidden not in warm_tier_upper, (
        f"Profile Warm Tier migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE PROFILE_WARM_TIER_JOURNAL_COMMITS",
    "UNIQUE (TENANT_ID, PROFILE_ID, PROFILE_WRITE_EPOCH, JOURNAL_SEQUENCE)",
    "UNIQUE (TRANSACTION_BARRIER)",
    "CHANGED_FILE_COUNT + REUSED_CHUNK_COUNT <= 50000",
    "UPLOADED_BYTES <= 67108864",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_PROFILE_WARM_TIER_IDENTITY",
    "VALIDATE CONSTRAINT CHK_PROFILE_WARM_TIER_COUNTS",
):
    assert invariant in warm_tier_upper, (
        f"Profile Warm Tier migration lacks rolling invariant: {invariant}"
    )
for version, migration_text in enterprise_migrations.items():
    upper_migration = migration_text.upper()
    assert "DROP COLUMN" not in upper_migration, f"{version} drops a column"
    assert "RENAME COLUMN" not in upper_migration, f"{version} renames a column"
    assert "ALTER COLUMN" not in upper_migration, f"{version} alters a column"

migration = enterprise_migrations["V020__media_and_extension_sampling.sql"]
for column in (
    "certified_media_slots",
    "reserved_media_slots",
    "supports_media",
    "media_workload",
    "requested_media_streams",
    "media_bitrate_kbps",
    "sampling_tier",
    "sampling_cpu_budget_millis",
):
    declaration = re.search(
        rf"ADD COLUMN\s+{column}\s+[^,;]+", migration, flags=re.IGNORECASE
    )
    assert declaration, f"missing additive column {column}"
    assert "DEFAULT" in declaration.group(0).upper(), f"{column} lacks N-1 default"

governance_migration = enterprise_migrations[
    "V021__enterprise_governance_evidence.sql"
]
excluded_from_sla = re.search(
    r"ADD COLUMN\s+excluded_from_sla\s+[^,;]+",
    governance_migration,
    flags=re.IGNORECASE,
)
assert excluded_from_sla, "missing additive excluded_from_sla column"
assert "DEFAULT FALSE" in excluded_from_sla.group(0).upper()

resource_stream_migration = read(
    "database/migrations/V026__session_resource_event_stream.sql"
)
resource_stream_upper = resource_stream_migration.upper()
assert "CREATE SEQUENCE" not in resource_stream_upper, (
    "resource stream must not use connection-cached PostgreSQL sequence cursors"
)
for invariant in (
    "CREATE TABLE SESSION_RESOURCE_STREAM_CURSORS",
    "PRIMARY KEY (TENANT_ID, SESSION_ID)",
    "PARTITION BY TENANT_ID, SESSION_ID",
    "ON CONFLICT (TENANT_ID, SESSION_ID)",
    "BEFORE INSERT ON SESSION_RESOURCE_SAMPLES",
    "BEFORE INSERT ON SESSION_RESOURCE_EVENTS",
):
    assert invariant in resource_stream_upper, (
        f"resource stream migration lacks commit-ordered invariant: {invariant}"
    )

unified_session_stream_migration = read(
    "database/migrations/V059__unified_session_event_stream.sql"
)
unified_session_stream_upper = unified_session_stream_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in unified_session_stream_upper, (
        f"unified Session stream migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE SESSION_EVENT_ENVELOPES",
    "PRIMARY KEY (TENANT_ID, SESSION_ID, STREAM_SEQUENCE)",
    "FOREIGN KEY (SESSION_ID, TENANT_ID)",
    "REFERENCES SESSIONS(ID, TENANT_ID) ON DELETE CASCADE",
    "ON CONFLICT (TENANT_ID, SESSION_ID)",
    "AFTER INSERT ON SESSION_RESOURCE_SAMPLES",
    "AFTER INSERT ON SESSION_RESOURCE_EVENTS",
    "AFTER INSERT ON SESSION_SAFETY_LEASE_EVENTS",
    "AFTER INSERT OR UPDATE ON BROWSER_STATES",
    "AFTER INSERT ON AUDIT_EVENTS",
    "AFTER INSERT OR UPDATE ON SESSIONS",
    "AFTER INSERT OR UPDATE ON EXCLUSIVE_OPERATIONS",
    "AFTER INSERT OR UPDATE ON AGENT_TASKS",
    "'BROWSER_STATE'",
    "'AUDIT_EVENT'",
    "'OPERATION'",
    "'AGENT_TASK'",
):
    assert invariant in unified_session_stream_upper, (
        f"unified Session stream lacks rolling invariant: {invariant}"
    )

proxy_rebind_migration = read(
    "database/migrations/V060__safe_proxy_rebind_workflow.sql"
)
proxy_rebind_upper = proxy_rebind_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in proxy_rebind_upper, (
        f"proxy rebind migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "ADD COLUMN WORKFLOW_TYPE TEXT NOT NULL DEFAULT 'NODE_MIGRATION'",
    "ADD COLUMN TARGET_PROXY_BINDING_PROFILE_ID TEXT",
    "ADD COLUMN TARGET_PROXY_BINDING_VERSION BIGINT",
    "CHECK (WORKFLOW_TYPE IN ('NODE_MIGRATION', 'PROXY_REBIND')) NOT VALID",
    "SESSION_MIGRATIONS_PROXY_REBIND_SNAPSHOT_CHECK",
    "SESSION_MIGRATIONS_TARGET_PROXY_BINDING_FK",
    "ON DELETE RESTRICT NOT VALID",
    "VALIDATE CONSTRAINT SESSION_MIGRATIONS_WORKFLOW_TYPE_CHECK",
    "VALIDATE CONSTRAINT SESSION_MIGRATIONS_PROXY_REBIND_SNAPSHOT_CHECK",
    "VALIDATE CONSTRAINT SESSION_MIGRATIONS_TARGET_PROXY_BINDING_FK",
    "CREATE UNIQUE INDEX UQ_SESSION_PROXY_REBIND_IDEMPOTENCY",
    "WHERE WORKFLOW_TYPE = 'PROXY_REBIND'",
):
    assert invariant in proxy_rebind_upper, (
        f"proxy rebind migration lacks rolling invariant: {invariant}"
    )

global_search_migration = read(
    "database/migrations/V061__bounded_global_search_indexes.sql"
)
global_search_upper = global_search_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in global_search_upper, (
        f"global search migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE EXTENSION IF NOT EXISTS PG_TRGM",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_SESSIONS_GLOBAL_SEARCH_TRGM",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_PROFILES_GLOBAL_SEARCH_TRGM",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_WORKSPACE_GROUPS_GLOBAL_SEARCH_TRGM",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_WORKSPACE_TAGS_GLOBAL_SEARCH_TRGM",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_RUNTIME_BUILDS_GLOBAL_SEARCH_TRGM",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_BROWSER_NODES_GLOBAL_SEARCH_TRGM",
    "METADATA->>'DISPLAYNAME'",
):
    assert invariant in global_search_upper, (
        f"global search migration lacks rolling invariant: {invariant}"
    )
assert "CAST(METADATA AS TEXT)" not in global_search_upper
global_search_config = read(
    "database/migrations/V061__bounded_global_search_indexes.sql.conf"
)
assert "executeInTransaction=false" in global_search_config

observer_evidence_migration = read(
    "database/migrations/V062__observer_evidence_access_governance.sql"
)
observer_evidence_upper = observer_evidence_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in observer_evidence_upper, (
        f"Observer evidence migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "ADD CONSTRAINT CHK_SESSION_EVIDENCE_KIND_V2",
    "'OBSERVER_MANUAL'",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_SESSION_EVIDENCE_KIND_V2",
    "RENAME CONSTRAINT CHK_SESSION_EVIDENCE_KIND_V2 TO CHK_SESSION_EVIDENCE_KIND",
    "CREATE TABLE SESSION_EVIDENCE_CAPTURE_REQUESTS",
    "CREATE TABLE SESSION_EVIDENCE_ACCESS_GRANTS",
    "UNIQUE (TENANT_ID, ACTOR_ID, IDEMPOTENCY_KEY)",
    "STATE IN ('ISSUED', 'REDEEMING', 'REDEEMED', 'FAILED')",
    "EXPIRES_AT <= CREATED_AT + INTERVAL '5 MINUTES'",
    "EXECUTE FUNCTION ENFORCE_SESSION_EVIDENCE_SCOPE()",
):
    assert invariant in observer_evidence_upper, (
        f"Observer evidence migration lacks rolling invariant: {invariant}"
    )

browser_activity_migration = read(
    "database/migrations/V028__browser_activity_safety_signals.sql"
)
browser_activity_upper = browser_activity_migration.upper()
for invariant in (
    "ADD CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK_V2",
    "NOT VALID",
    "VALIDATE CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK_V2",
    "DROP CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK",
    "RENAME CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK_V2",
    "'FILE_UPLOAD_ACTIVE'",
    "'FILE_DOWNLOAD_ACTIVE'",
    "'FORM_SUBMISSION_ACTIVE'",
):
    assert invariant in browser_activity_upper, (
        f"browser activity migration lacks rolling invariant: {invariant}"
    )

browser_transaction_migration = read(
    "database/migrations/V097__browser_transaction_safety_signals.sql"
)
browser_transaction_upper = browser_transaction_migration.upper()
for invariant in (
    "ADD CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK_V3",
    "NOT VALID",
    "VALIDATE CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK_V3",
    "DROP CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK",
    "RENAME CONSTRAINT SESSION_SAFETY_SIGNALS_SIGNAL_TYPE_CHECK_V3",
    "'SPA_MUTATION_ACTIVE'",
    "'PAYMENT_OR_SECURITY_ACTIVE'",
    "'CRITICAL_TRANSACTION_ACTIVE'",
):
    assert invariant in browser_transaction_upper, (
        f"browser transaction migration lacks rolling invariant: {invariant}"
    )

browser_transaction_policy_migration = read(
    "database/migrations/V098__browser_transaction_site_policy.sql"
)
browser_transaction_policy_upper = browser_transaction_policy_migration.upper()
for invariant in (
    "ADD COLUMN PAYMENT_SECURITY_ROUTE_PREFIXES JSONB NOT NULL DEFAULT '[]'",
    "ADD COLUMN CRITICAL_TRANSACTION_ROUTE_PREFIXES JSONB NOT NULL DEFAULT '[]'",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_APPLICATION_RECOVERY_CONTRACT_TRANSACTION_ROUTES",
    "VALIDATE CONSTRAINT CHK_APPLICATION_RECOVERY_REVISION_TRANSACTION_ROUTES",
    "CREATE OR REPLACE FUNCTION SNAPSHOT_APPLICATION_RECOVERY_CONTRACT_REVISION()",
):
    assert invariant in browser_transaction_policy_upper, (
        f"browser transaction Site Policy migration lacks rolling invariant: {invariant}"
    )

recording_manifest_migration = read(
    "database/migrations/V099__session_recording_manifest_projection.sql"
)
recording_manifest_upper = recording_manifest_migration.upper()
for invariant in (
    "CREATE TABLE SESSION_RECORDINGS",
    "EVENT_ID                   TEXT NOT NULL UNIQUE",
    "MANIFEST_OBJECT_KEY        TEXT NOT NULL",
    "LEGAL_HOLD                 BOOLEAN NOT NULL DEFAULT FALSE",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_SESSION_RECORDING_COUNTS",
    "VALIDATE CONSTRAINT CHK_SESSION_RECORDING_MANIFEST",
):
    assert invariant in recording_manifest_upper, (
        f"recording manifest migration lacks rolling invariant: {invariant}"
    )
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in recording_manifest_upper, (
        f"recording manifest migration contains incompatible operation: {forbidden}"
    )

secure_debug_notification_migration = read(
    "database/migrations/V100__secure_debug_notification_admission.sql"
)
secure_debug_notification_upper = secure_debug_notification_migration.upper()
for invariant in (
    "CREATE OR REPLACE FUNCTION APPEND_WORKSPACE_NOTIFICATION()",
    "NEW.EVENT_TYPE LIKE 'SECURE_DEBUG_%'",
    "NEW.ACTION LIKE 'SECURE_DEBUG_%'",
):
    assert invariant in secure_debug_notification_upper, (
        f"Secure Debug notification admission migration lacks invariant: {invariant}"
    )
# Replacing only the function body keeps the V064 trigger binding intact, so an N-1 Control Plane
# keeps writing audit rows through the same trigger while the projection widens.
for forbidden in (
    "DROP COLUMN",
    "RENAME COLUMN",
    "ALTER COLUMN",
    "DROP TRIGGER",
    "CREATE TRIGGER",
    "DROP FUNCTION",
):
    assert forbidden not in secure_debug_notification_upper, (
        "Secure Debug notification admission migration contains incompatible operation: "
        f"{forbidden}"
    )

application_safety_migration = read(
    "database/migrations/V029__application_safety_leases.sql"
)
application_safety_upper = application_safety_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in application_safety_upper, (
        f"application safety migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE SESSION_SAFETY_LEASES",
    "CREATE TABLE SESSION_SAFETY_LEASE_EVENTS",
    "REFERENCES SESSIONS(ID) ON DELETE CASCADE",
    "CREATE INDEX IDX_SESSION_SAFETY_LEASES_TIMELINE",
    "EXECUTE FUNCTION ASSIGN_SESSION_RESOURCE_STREAM_SEQUENCE()",
    "CREATE UNIQUE INDEX UQ_SESSION_SAFETY_LEASE_EVENTS_STREAM_SEQUENCE",
    "'PAYMENT_OR_SECURITY'",
    "'CRITICAL_TRANSACTION'",
    "'BUSINESS_RECOVERY_UNKNOWN'",
):
    assert invariant in application_safety_upper, (
        f"application safety migration lacks rolling invariant: {invariant}"
    )

business_recovery_migration = read(
    "database/migrations/V030__application_business_recovery.sql"
)
business_recovery_upper = business_recovery_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in business_recovery_upper, (
        f"Business Recovery migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE APPLICATION_RECOVERY_CONTRACTS",
    "CREATE TABLE SESSION_APPLICATION_BINDINGS",
    "CREATE TABLE BUSINESS_RECOVERY_VALIDATIONS",
    "UNIQUE (TENANT_ID, APPLICATION_ID)",
    "REFERENCES SESSIONS(ID) ON DELETE CASCADE",
    "CREATE INDEX IDX_BUSINESS_RECOVERY_VALIDATIONS_LATEST",
    "'MANUAL_RECOVERY_REQUIRED'",
):
    assert invariant in business_recovery_upper, (
        f"Business Recovery migration lacks rolling invariant: {invariant}"
    )

extension_resources_migration = read(
    "database/migrations/V031__extension_runtime_resources.sql"
)
extension_resources_upper = extension_resources_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in extension_resources_upper
for invariant in (
    "ADD COLUMN EXTENSION_CPU_WEIGHT",
    "NOT NULL DEFAULT 100",
    "CHECK (EXTENSION_CPU_WEIGHT BETWEEN 1 AND 10000)",
):
    assert invariant in extension_resources_upper

media_encoder_slots_migration = read(
    "database/migrations/V032__media_encoder_runtime_slots.sql"
)
media_encoder_slots_upper = media_encoder_slots_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in media_encoder_slots_upper
for invariant in (
    "ADD COLUMN MEDIA_ENCODER_SLOTS INTEGER NOT NULL DEFAULT 0",
    "CHECK (",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_BROWSER_PLACEMENTS_MEDIA_ENCODER_SLOTS",
):
    assert invariant in media_encoder_slots_upper

workspace_groups_migration = read(
    "database/migrations/V033__workspace_groups.sql"
)
workspace_groups_upper = workspace_groups_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in workspace_groups_upper
for invariant in (
    "CREATE TABLE WORKSPACE_GROUPS",
    "ADD COLUMN GROUP_ID TEXT",
    "ON DELETE SET NULL",
    "NOT VALID",
    "VALIDATE CONSTRAINT FK_SESSIONS_WORKSPACE_GROUP",
    "CREATE INDEX IDX_SESSIONS_TENANT_GROUP",
):
    assert invariant in workspace_groups_upper

auto_recovery_migration = read(
    "database/migrations/V034__business_recovery_actions.sql"
)
auto_recovery_upper = auto_recovery_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in auto_recovery_upper
for invariant in (
    "ADD COLUMN RECOVERY_ACTION TEXT NOT NULL DEFAULT 'NONE'",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_APPLICATION_RECOVERY_ACTION",
    "CREATE TABLE BUSINESS_RECOVERY_ACTIONS",
    "'REQUESTED'",
    "'EXECUTING'",
    "'ACKNOWLEDGED'",
    "'COMMITTED'",
    "'FAILED'",
    "UNIQUE (MIGRATION_ID, ATTEMPT_NUMBER)",
    "'BUSINESS_RECOVERY_ACTION'",
):
    assert invariant in auto_recovery_upper

workspace_tags_migration = read(
    "database/migrations/V035__workspace_tags.sql"
)
workspace_tags_upper = workspace_tags_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in workspace_tags_upper
for invariant in (
    "CREATE TABLE WORKSPACE_TAGS",
    "CREATE TABLE SESSION_TAG_ASSIGNMENTS",
    "CREATE UNIQUE INDEX UQ_SESSIONS_ID_TENANT",
    "FOREIGN KEY (SESSION_ID, TENANT_ID)",
    "FOREIGN KEY (TAG_ID, TENANT_ID)",
    "ON DELETE CASCADE",
    "UNIQUE (SESSION_ID, TAG_ID)",
    "CREATE INDEX IDX_SESSION_TAG_ASSIGNMENTS_TENANT_SESSION",
    "SYSTEM:V035-BACKFILL",
    "REGEXP_SPLIT_TO_TABLE",
    "ON CONFLICT (SESSION_ID, TAG_ID) DO NOTHING",
):
    assert invariant in workspace_tags_upper

workspace_settings_migration = read(
    "database/migrations/V036__workspace_settings.sql"
)
workspace_settings_upper = workspace_settings_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in workspace_settings_upper
for invariant in (
    "CREATE TABLE WORKSPACE_SETTINGS",
    "TENANT_ID TEXT PRIMARY KEY",
    "FOREIGN KEY (DEFAULT_RUNTIME_BUILD_ID)",
    "REFERENCES RUNTIME_BUILDS(BUILD_ID)",
    "ADD COLUMN HUMAN_TAKEOVER_ENABLED BOOLEAN NOT NULL DEFAULT TRUE",
):
    assert invariant in workspace_settings_upper

agent_policy_migration = read("database/migrations/V037__session_agent_policy.sql")
agent_policy_upper = agent_policy_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in agent_policy_upper
for invariant in (
    "ADD COLUMN AGENT_POLICY TEXT NOT NULL DEFAULT 'BALANCED'",
    "CHK_SESSIONS_AGENT_POLICY",
    "CHK_AGENT_TASKS_AGENT_POLICY",
    "UPDATE AGENT_TASKS TASK",
):
    assert invariant in agent_policy_upper

session_extension_migration = read("database/migrations/V038__session_extension_binding.sql")
session_extension_upper = session_extension_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in session_extension_upper
for invariant in (
    "ADD COLUMN EXTENSION_IDS JSONB NOT NULL DEFAULT '[]'::JSONB",
    "UPDATE SESSIONS SESSION",
    "FROM SESSION_RESOURCE_DEMANDS DEMAND",
    "CHK_SESSIONS_EXTENSION_IDS",
):
    assert invariant in session_extension_upper

trusted_extension_recovery_migration = read(
    "database/migrations/V039__trusted_extension_recovery.sql"
)
trusted_extension_recovery_upper = trusted_extension_recovery_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in trusted_extension_recovery_upper
for invariant in (
    "ADD COLUMN RECOVERY_EXTENSION_ID TEXT",
    "ADD COLUMN TARGET_EXTENSION_ID TEXT",
    "'RESTART_EXTENSION'",
    "REQUIRED_EXTENSION_IDS ? RECOVERY_EXTENSION_ID",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_APPLICATION_RECOVERY_EXTENSION_TARGET",
    "VALIDATE CONSTRAINT CHK_BUSINESS_RECOVERY_ACTION_TARGET",
):
    assert invariant in trusted_extension_recovery_upper

tenant_route_migration = read(
    "database/migrations/V040__authoritative_tenant_shard_routes.sql"
)
tenant_route_upper = tenant_route_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in tenant_route_upper
for invariant in (
    "CREATE TABLE COORDINATOR_TENANT_ROUTES",
    "CREATE TABLE COORDINATOR_SESSION_ROUTES",
    "CREATE TABLE COORDINATOR_ROUTE_MIGRATIONS",
    "ADD COLUMN ROUTE_EPOCH BIGINT NOT NULL DEFAULT 1",
    "DEFERRABLE INITIALLY DEFERRED",
    "REFERENCES SESSIONS(ID, TENANT_ID)",
    "VALIDATE CONSTRAINT CHK_COORDINATOR_OWNERSHIP_ROUTE_EPOCH",
):
    assert invariant in tenant_route_upper

sharded_dispatch_migration = read(
    "database/migrations/V041__sharded_node_command_dispatch.sql"
)
sharded_dispatch_upper = sharded_dispatch_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in sharded_dispatch_upper
for invariant in (
    "ADD COLUMN ROUTE_EPOCH BIGINT",
    "ADD COLUMN COORDINATOR_SHARD_ID INTEGER",
    "ADD COLUMN DISPATCH_OWNER TEXT",
    "ADD COLUMN DISPATCH_LEASE_UNTIL TIMESTAMPTZ",
    "CREATE TABLE COORDINATOR_DISPATCH_WORKERS",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_OUTBOX_ROUTE_BINDING_COMPLETE",
):
    assert invariant in sharded_dispatch_upper

sharded_dispatch_index = read(
    "database/online-migrations/create_outbox_node_command_shard_claim_index.sql"
)
sharded_dispatch_index_upper = sharded_dispatch_index.upper()
assert "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_OUTBOX_NODE_COMMAND_SHARD_CLAIM" in (
    sharded_dispatch_index_upper
)
assert "ALTER TABLE" not in sharded_dispatch_index_upper

resource_cost_migration = read(
    "database/migrations/V042__session_resource_cost_and_maximum_mitigation.sql"
)
resource_cost_upper = resource_cost_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in resource_cost_upper
for invariant in (
    "ADD COLUMN CURRENT_HOURLY_COST NUMERIC(12,6)",
    "ADD COLUMN COST_PRICING_VERSION TEXT",
    "ADD COLUMN LAST_COST_EVALUATED_AT TIMESTAMPTZ",
    "ADD COLUMN MAXIMUM_MITIGATION_AT TIMESTAMPTZ",
    "ADD COLUMN MAXIMUM_MITIGATION_OPERATION_ID TEXT",
    "CREATE TABLE SESSION_RESOURCE_COST_SNAPSHOTS",
    "NOT VALID",
    "VALIDATE CONSTRAINT CK_SESSION_RESOURCE_POLICY_CURRENT_COST",
    "VALIDATE CONSTRAINT CK_SESSION_RESOURCE_POLICY_MAXIMUM_MITIGATION",
):
    assert invariant in resource_cost_upper, (
        f"resource cost migration lacks rolling invariant: {invariant}"
    )

tab_resource_migration = read(
    "database/migrations/V043__browser_placement_tab_resource_actuators.sql"
)
tab_resource_upper = tab_resource_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in tab_resource_upper
for invariant in (
    "ADD COLUMN BACKGROUND_TABS_FROZEN BOOLEAN NOT NULL DEFAULT FALSE",
    "ADD COLUMN NEW_TABS_BLOCKED BOOLEAN NOT NULL DEFAULT FALSE",
):
    assert invariant in tab_resource_upper, (
        f"tab resource migration lacks rolling invariant: {invariant}"
    )

extension_background_migration = read(
    "database/migrations/V044__browser_placement_paused_extensions.sql"
)
extension_background_upper = extension_background_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in extension_background_upper
for invariant in (
    "ADD COLUMN PAUSED_EXTENSION_IDS JSONB NOT NULL DEFAULT '[]'::JSONB",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_BROWSER_PLACEMENTS_PAUSED_EXTENSION_IDS_ARRAY",
):
    assert invariant in extension_background_upper, (
        f"extension background migration lacks rolling invariant: {invariant}"
    )

success_trace_migration = read(
    "database/migrations/V045__browser_placement_success_trace_sampling.sql"
)
success_trace_upper = success_trace_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in success_trace_upper
for invariant in (
    "ADD COLUMN SUCCESS_TRACE_SAMPLE_PERCENT INTEGER NOT NULL DEFAULT 100",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_BROWSER_PLACEMENTS_SUCCESS_TRACE_SAMPLE_PERCENT",
):
    assert invariant in success_trace_upper, (
        f"success Trace migration lacks rolling invariant: {invariant}"
    )

observer_frame_rate_migration = read(
    "database/migrations/V046__browser_placement_observer_frame_rate.sql"
)
observer_frame_rate_upper = observer_frame_rate_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in observer_frame_rate_upper
for invariant in (
    "ADD COLUMN OBSERVER_FRAME_RATE_FPS INTEGER NOT NULL DEFAULT 0",
    "SET OBSERVER_FRAME_RATE_FPS = 30",
    "WHERE REQUIRES_DESKTOP",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_BROWSER_PLACEMENTS_OBSERVER_FRAME_RATE_FPS",
):
    assert invariant in observer_frame_rate_upper, (
        f"Observer frame-rate migration lacks rolling invariant: {invariant}"
    )

video_recording_migration = read(
    "database/migrations/V047__session_video_recording_actuator.sql"
)
video_recording_upper = video_recording_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in video_recording_upper
for invariant in (
    "ADD COLUMN VIDEO_RECORDING_REQUESTED BOOLEAN NOT NULL DEFAULT FALSE",
    "ADD COLUMN VIDEO_RECORDING_ENABLED BOOLEAN NOT NULL DEFAULT FALSE",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_BROWSER_PLACEMENTS_VIDEO_RECORDING_STATE",
):
    assert invariant in video_recording_upper, (
        f"video recording migration lacks rolling invariant: {invariant}"
    )

resource_template_migration = read(
    "database/migrations/V048__public_resource_template_pricing.sql"
)
resource_template_upper = resource_template_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in resource_template_upper
for invariant in (
    "ADD COLUMN RESOURCE_TEMPLATE TEXT",
    "CREATE TRIGGER TRG_ENTERPRISE_COST_RESOURCE_TEMPLATE",
    "BEFORE INSERT OR UPDATE OF RESOURCE_CLASS, RESOURCE_TEMPLATE",
    "ALTER COLUMN RESOURCE_TEMPLATE SET NOT NULL",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_ENTERPRISE_COST_RESOURCE_TEMPLATE",
):
    assert invariant in resource_template_upper, (
        f"resource template migration lacks rolling invariant: {invariant}"
    )

screenshot_evidence_migration = read(
    "database/migrations/V049__session_screenshot_evidence.sql"
)
screenshot_evidence_upper = screenshot_evidence_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER COLUMN"):
    assert forbidden not in screenshot_evidence_upper
for invariant in (
    "ADD COLUMN SUCCESS_SCREENSHOT_SAMPLE_PERCENT INTEGER NOT NULL DEFAULT 100",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_BROWSER_PLACEMENTS_SUCCESS_SCREENSHOT_SAMPLE_PERCENT",
    "CREATE TABLE SESSION_EVIDENCE",
    "OBJECT_KEY",
    "RESULT IN ('COMMITTED', 'FAILED')",
):
    assert invariant in screenshot_evidence_upper, (
        f"screenshot evidence migration lacks rolling invariant: {invariant}"
    )

recovery_approval_migration = read(
    "database/migrations/V050__application_recovery_contract_approval.sql"
)
recovery_approval_upper = recovery_approval_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in recovery_approval_upper
for invariant in (
    "CREATE TABLE APPLICATION_RECOVERY_CONTRACT_APPROVALS",
    "STATE IN ('REQUESTED', 'APPROVED', 'REJECTED')",
    "CREATE UNIQUE INDEX UQ_RECOVERY_CONTRACT_PENDING_APPROVAL",
    "CREATE UNIQUE INDEX UQ_RECOVERY_CONTRACT_APPROVED_VERSION",
    "ADD COLUMN CONTRACT_VERSION BIGINT",
    "CREATE TRIGGER TRG_SESSION_APPLICATION_CONTRACT_VERSION",
    "BEFORE INSERT ON SESSION_APPLICATION_BINDINGS",
    "ALTER COLUMN CONTRACT_VERSION SET NOT NULL",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_SESSION_APPLICATION_CONTRACT_VERSION",
):
    assert invariant in recovery_approval_upper, (
        f"recovery approval migration lacks rolling invariant: {invariant}"
    )

recovery_revision_migration = read(
    "database/migrations/V051__application_recovery_contract_revisions.sql"
)
recovery_revision_upper = recovery_revision_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in recovery_revision_upper
for invariant in (
    "CREATE TABLE APPLICATION_RECOVERY_CONTRACT_REVISIONS",
    "PRIMARY KEY (CONTRACT_ID, CONTRACT_VERSION)",
    "CREATE TRIGGER TRG_APPLICATION_RECOVERY_CONTRACT_REVISION",
    "AFTER INSERT OR UPDATE ON APPLICATION_RECOVERY_CONTRACTS",
    "ON CONFLICT (CONTRACT_ID, CONTRACT_VERSION) DO NOTHING",
    "CREATE TRIGGER TRG_APPLICATION_RECOVERY_REVISION_IMMUTABLE",
    "BEFORE UPDATE OR DELETE ON APPLICATION_RECOVERY_CONTRACT_REVISIONS",
    "ADD CONSTRAINT FK_SESSION_APPLICATION_BINDING_REVISION",
    "ADD CONSTRAINT FK_RECOVERY_CONTRACT_APPROVAL_REVISION",
    "NOT VALID",
    "CREATE TABLE SESSION_APPLICATION_REBIND_OPERATIONS",
    "REFERENCES EXCLUSIVE_OPERATIONS(OPERATION_ID)",
):
    assert invariant in recovery_revision_upper, (
        f"recovery revision migration lacks rolling invariant: {invariant}"
    )

provider_evidence_migration = read(
    "database/migrations/V052__business_recovery_provider_evidence.sql"
)
provider_evidence_upper = provider_evidence_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER COLUMN"):
    assert forbidden not in provider_evidence_upper
for invariant in (
    "ADD COLUMN REQUIRED_PROVIDER_EVIDENCE JSONB NOT NULL DEFAULT '[]'",
    "CREATE OR REPLACE FUNCTION SNAPSHOT_APPLICATION_RECOVERY_CONTRACT_REVISION()",
    "CREATE TABLE BUSINESS_RECOVERY_PROVIDER_EVIDENCE",
    "EVIDENCE_TYPE IN ('ACCOUNT', 'TENANT_WORKSPACE', 'PERMISSION', 'BUSINESS_ENTITY')",
    "OUTCOME IN ('MATCH', 'MISMATCH', 'UNKNOWN')",
    "PROVIDER_REFERENCE_HASH",
    "EXPIRES_AT <= OBSERVED_AT + INTERVAL '15 MINUTES'",
    "REFERENCES APPLICATION_RECOVERY_CONTRACT_REVISIONS",
    "CREATE TRIGGER TRG_BUSINESS_RECOVERY_PROVIDER_EVIDENCE_IMMUTABLE",
    "BEFORE UPDATE ON BUSINESS_RECOVERY_PROVIDER_EVIDENCE",
):
    assert invariant in provider_evidence_upper, (
        f"Provider evidence migration lacks rolling invariant: {invariant}"
    )

saved_view_migration = read("database/migrations/V053__environment_saved_views.sql")
saved_view_upper = saved_view_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER COLUMN"):
    assert forbidden not in saved_view_upper
for invariant in (
    "CREATE TABLE ENVIRONMENT_SAVED_VIEWS",
    "SCOPE IN ('PERSONAL', 'WORKSPACE')",
    "PRIMARY_VIEW IN ('ALL', 'RUNNING', 'STOPPED', 'ABNORMAL')",
    "CREATE UNIQUE INDEX UQ_ENVIRONMENT_SAVED_VIEW_PERSONAL_NAME",
    "WHERE SCOPE = 'PERSONAL'",
    "CREATE UNIQUE INDEX UQ_ENVIRONMENT_SAVED_VIEW_WORKSPACE_NAME",
    "WHERE SCOPE = 'WORKSPACE'",
):
    assert invariant in saved_view_upper, (
        f"Environment Saved View migration lacks rolling invariant: {invariant}"
    )

saved_view_filter_migration = read(
    "database/migrations/V067__environment_saved_view_workspace_filters.sql"
)
saved_view_filter_upper = saved_view_filter_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER COLUMN"):
    assert forbidden not in saved_view_filter_upper, (
        f"Saved View filter migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "ADD COLUMN GROUP_ID TEXT",
    "ADD COLUMN TAG_IDS JSONB NOT NULL DEFAULT '[]'::JSONB",
    "ADD COLUMN TAG_MATCH TEXT NOT NULL DEFAULT 'ANY'",
    "CREATE OR REPLACE FUNCTION IS_VALID_ENVIRONMENT_SAVED_VIEW_TAG_IDS",
    "COUNT(*) = COUNT(DISTINCT VALUE)",
    "IS_VALID_ENVIRONMENT_SAVED_VIEW_TAG_IDS(TAG_IDS)",
    "TAG_MATCH IN ('ANY', 'ALL')",
    "CREATE UNIQUE INDEX UQ_WORKSPACE_GROUPS_ID_TENANT_SAVED_VIEW",
    "FOREIGN KEY (GROUP_ID, TENANT_ID)",
    "REFERENCES WORKSPACE_GROUPS(GROUP_ID, TENANT_ID)",
    "ON DELETE SET NULL (GROUP_ID)",
    "NOT VALID",
    "VALIDATE CONSTRAINT FK_ENVIRONMENT_SAVED_VIEW_GROUP",
):
    assert invariant in saved_view_filter_upper, (
        f"Saved View filter migration lacks rolling invariant: {invariant}"
    )

profile_import_migration = read(
    "database/migrations/V055__profile_checkpoint_imports.sql"
)
profile_import_upper = profile_import_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER TABLE"):
    assert forbidden not in profile_import_upper, (
        f"Profile Import migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE PROFILE_IMPORT_JOBS",
    "UNIQUE (TENANT_ID, OWNER_ACTOR_ID, IDEMPOTENCY_KEY)",
    "STATE IN ('REQUESTED', 'UPLOADING', 'VALIDATING', 'COMMITTED', 'FAILED')",
    "ARCHIVE_SIZE_BYTES BETWEEN 1 AND 268435456",
    "RAW ARCHIVE BYTES NEVER ENTER POSTGRESQL",
):
    assert invariant in profile_import_upper, (
        f"Profile Import migration lacks rolling invariant: {invariant}"
    )

migration_retry_migration = read(
    "database/migrations/V056__session_migration_target_retry.sql"
)
migration_retry_upper = migration_retry_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER COLUMN"):
    assert forbidden not in migration_retry_upper, (
        f"Migration target retry contains incompatible operation: {forbidden}"
    )
for declaration in (
    "ADD COLUMN TARGET_ATTEMPT INTEGER NOT NULL DEFAULT 0",
    "ADD COLUMN MAXIMUM_TARGET_ATTEMPTS INTEGER NOT NULL DEFAULT 3",
    "ADD COLUMN FAILED_TARGET_NODE_IDS JSONB NOT NULL DEFAULT '[]'::JSONB",
):
    assert declaration in migration_retry_upper, (
        f"Migration target retry lacks N-1 default: {declaration}"
    )
for invariant in (
    "ADD COLUMN TARGET_CLEANUP_OPERATION_ID TEXT",
    "ADD COLUMN LAST_TARGET_FAILURE_REASON TEXT",
    "CHECK (JSONB_TYPEOF(FAILED_TARGET_NODE_IDS) = 'ARRAY') NOT VALID",
    "VALIDATE CONSTRAINT SESSION_MIGRATIONS_TARGET_ATTEMPT_CHECK",
    "VALIDATE CONSTRAINT SESSION_MIGRATIONS_FAILED_TARGET_NODES_CHECK",
    "DROP CONSTRAINT SESSION_MIGRATIONS_PHASE_CHECK",
    "'TARGET_CLEANUP'",
    "VALIDATE CONSTRAINT SESSION_MIGRATIONS_PHASE_CHECK",
):
    assert invariant in migration_retry_upper, (
        f"Migration target retry lacks rolling invariant: {invariant}"
    )

proto = read("packages/contracts/proto/node/v1/node_command.proto")
service = proto.split("service NodeControlService {", 1)[1].split("}", 1)[0]
assert (
    "rpc UploadProfileImport(stream UploadProfileImportRequest) returns (UploadProfileImportResponse);"
    in service
)
assert (
    "rpc PresignEvidenceDownload(PresignEvidenceDownloadRequest)"
    in service
    and "returns (PresignEvidenceDownloadResponse);" in service
)
assert (
    "rpc PresignProfileExportDownload(PresignProfileExportDownloadRequest)"
    in service
    and "returns (PresignProfileExportDownloadResponse);" in service
)
warm_tier_message = proto.split("message ProfileWarmTierSyncedEvent {", 1)[1].split(
    "}", 1
)[0]
for field_name, field_tag in (
    ("session_id", 1),
    ("node_id", 2),
    ("profile_id", 3),
    ("profile_write_epoch", 4),
    ("journal_sequence", 5),
    ("transaction_barrier", 6),
    ("changed_file_count", 7),
    ("deleted_file_count", 8),
    ("reused_chunk_count", 9),
    ("uploaded_bytes", 10),
    ("deferred_group_count", 11),
    ("manifest_sha256", 12),
    ("committed_at_ms", 13),
):
    assert re.search(rf"\b{field_name}\s*=\s*{field_tag}\s*;", warm_tier_message), (
        f"ProfileWarmTierSyncedEvent field {field_name} must keep tag {field_tag}"
    )
for message_name, expected_tags in (
    (
        "UploadProfileImportRequest",
        {
            "import_id": 1,
            "tenant_id": 2,
            "profile_id": 3,
            "checkpoint_id": 4,
            "runtime_build_id": 5,
            "archive_sha256": 6,
            "archive_size_bytes": 7,
            "offset": 8,
            "data": 9,
        },
    ),
    (
        "UploadProfileImportResponse",
        {
            "import_id": 1,
            "node_id": 2,
            "profile_id": 3,
            "checkpoint_id": 4,
            "checkpoint_epoch": 5,
            "profile_write_epoch": 6,
            "core_size_bytes": 7,
            "checkpoint_file_count": 8,
            "archive_sha256": 9,
            "archive_size_bytes": 10,
        },
    ),
    (
        "PresignEvidenceDownloadRequest",
        {
            "grant_id": 1,
            "tenant_id": 2,
            "profile_id": 3,
            "session_id": 4,
            "evidence_id": 5,
            "content_sha256": 6,
            "content_bytes": 7,
            "expires_in_seconds": 8,
        },
    ),
    (
        "PresignEvidenceDownloadResponse",
        {
            "grant_id": 1,
            "node_id": 2,
            "evidence_id": 3,
            "download_url": 4,
            "expires_at_ms": 5,
        },
    ),
    (
        "PresignProfileExportDownloadRequest",
        {
            "grant_id": 1,
            "tenant_id": 2,
            "profile_id": 3,
            "checkpoint_id": 4,
            "expires_in_seconds": 5,
        },
    ),
    (
        "PresignProfileExportDownloadResponse",
        {
            "grant_id": 1,
            "node_id": 2,
            "profile_id": 3,
            "checkpoint_id": 4,
            "archive_sha256": 5,
            "archive_size_bytes": 6,
            "download_url": 7,
            "expires_at_ms": 8,
        },
    ),
    (
        "CaptureObserverScreenshotCommand",
        {
            "session_id": 1,
            "capture_id": 2,
        },
    ),
):
    message = proto.split(f"message {message_name} {{", 1)[1].split("}", 1)[0]
    observed_tags = {
        name: int(tag)
        for name, tag in re.findall(
            r"^\s*[A-Za-z0-9_.]+\s+([a-z0-9_]+)\s*=\s*(\d+);",
            message,
            flags=re.MULTILINE,
        )
    }
    assert observed_tags == expected_tags

command_envelope = proto.split("message CommandEnvelope {", 1)[1].split("}", 1)[0]
command_tags = {
    name: int(tag)
    for name, tag in re.findall(
        r"^\s*[a-z0-9_]+\s+([a-z0-9_]+)\s*=\s*(\d+);",
        command_envelope,
        flags=re.MULTILINE,
    )
}
assert len(command_tags.values()) == len(set(command_tags.values())), (
    "command envelope protobuf field number reused"
)
assert command_tags["route_epoch"] == 13
assert command_tags["coordinator_shard_id"] == 14

capacity = proto.split("message ReportCapacityRequest {", 1)[1].split("}", 1)[0]
tags = {
    name: int(tag)
    for name, tag in re.findall(
        r"^\s*(?:map<[^>]+>|[a-z0-9_]+)\s+([a-z0-9_]+)\s*=\s*(\d+);",
        capacity,
        flags=re.MULTILINE,
    )
}
assert len(tags.values()) == len(set(tags.values())), "protobuf field number reused"
assert tags["certified_media_slots"] == 15
assert tags["supports_media"] == 16
assert tags["memory_psi_some_avg10"] == 20

node_agent = read("apps/browser-node/crates/node-agent/src/main.rs")
capacity_repository = read(
    "apps/control-plane/src/main/java/io/browsercloud/persistence/BrowserNodeJpaRepository.java"
)
capacity_service = read(
    "apps/control-plane/src/main/java/io/browsercloud/application/BrowserCapacityApplicationService.java"
)
migration_service = read(
    "apps/control-plane/src/main/java/io/browsercloud/application/SessionMigrationApplicationService.java"
)
assert '"startRuntimeGenerationFloor".to_owned()' in node_agent
assert '"v1".to_owned()' in node_agent
assert "labels->>'startRuntimeGenerationFloor' = 'v1'" in capacity_repository
assert "lockMigrationPlacementCandidates" in capacity_repository
assert "NO_MIGRATION_TARGET_WITH_GENERATION_FLOOR_CAPABILITY" in capacity_service
assert "reserveMigrationTarget" in migration_service
assert '"proxyProviderDescriptor".to_owned()' in node_agent
assert "PROXY_DESCRIPTOR_CAPABILITY" in capacity_service
assert "NO_PROXY_DESCRIPTOR_CAPABLE_NODE" in capacity_service
assert '"COMMAND_IN_PROGRESS"' in node_agent
assert "dispatch_durable(command)" in node_agent
assert "tokio::spawn(async move" in node_agent

resource_report = proto.split("message ReportSessionResourcesRequest {", 1)[1].split(
    "}", 1
)[0]
resource_tags = {
    name: (field_type, int(tag))
    for field_type, name, tag in re.findall(
        r"^\s*(optional\s+)?(?:map<[^>]+>|[a-z0-9_]+)\s+([a-z0-9_]+)\s*=\s*(\d+);",
        resource_report,
        flags=re.MULTILINE,
    )
}
resource_tag_numbers = [tag for _, tag in resource_tags.values()]
assert len(resource_tag_numbers) == len(set(resource_tag_numbers)), (
    "resource report protobuf field number reused"
)
for name, expected_tag in (
    ("active_upload_count", 28),
    ("active_download_count", 29),
    ("active_form_submission_count", 30),
    ("active_spa_mutation_count", 35),
    ("active_payment_or_security_count", 36),
    ("active_critical_transaction_count", 37),
    ("proxy_probe_succeeded", 31),
    ("proxy_probe_latency_ms", 32),
    ("proxy_observed_exit_ip", 33),
    ("actual_resource_class", 40),
    ("actual_cpu_millis", 41),
    ("actual_memory_request_mib", 42),
    ("actual_memory_limit_mib", 43),
    ("actual_pid_limit", 44),
    ("actual_tab_budget", 45),
    ("actual_state_collector_budget_percent", 46),
    ("actual_remote_desktop_bitrate_kbps", 47),
    ("actual_extension_cpu_weight", 48),
    ("actual_media_encoder_slots", 49),
    ("actual_freeze_background_tabs", 50),
    ("actual_block_new_tabs", 51),
    ("actual_success_trace_sample_percent", 53),
    ("actual_observer_frame_rate_fps", 54),
    ("actual_video_recording_enabled", 55),
    ("actual_success_screenshot_sample_percent", 56),
):
    qualifier, actual_tag = resource_tags[name]
    assert qualifier is not None, f"{name} must remain optional for N/N-1"
    assert actual_tag == expected_tag
assert resource_tags["proxy_probe_error_code"][1] == 34
assert "ExtensionBackgroundPolicy actual_extension_background_policy = 52;" in resource_report

for message_name, fields in (
    (
        "StartRuntimeCommand",
        (
            ("extension_ids", 20, False),
            ("extension_cpu_weight", 21, True),
            ("media_encoder_slots", 22, True),
            ("freeze_background_tabs", 23, True),
            ("block_new_tabs", 24, True),
            ("extension_background_policy", 25, False),
            ("success_trace_sample_percent", 26, True),
            ("observer_frame_rate_fps", 27, True),
            ("video_recording_enabled", 28, True),
            ("success_screenshot_sample_percent", 29, True),
            ("minimum_browser_generation", 30, False),
            ("proxy_provider_id", 31, True),
            ("proxy_expected_exit_ip", 32, True),
            ("proxy_credential_ref", 33, True),
            ("browser_transaction_expected_origins", 34, False),
            ("payment_security_route_prefixes", 35, False),
            ("critical_transaction_route_prefixes", 36, False),
            ("browser_transaction_policy_hash", 37, False),
            ("browser_transaction_policy_version", 38, False),
        ),
    ),
    (
        "AdjustRuntimeResourcesCommand",
        (
            ("extension_cpu_weight", 15, True),
            ("media_encoder_slots", 16, True),
            ("freeze_background_tabs", 17, True),
            ("block_new_tabs", 18, True),
            ("extension_background_policy", 19, False),
            ("extension_ids", 20, False),
            ("success_trace_sample_percent", 21, True),
            ("observer_frame_rate_fps", 22, True),
            ("video_recording_enabled", 23, True),
            ("success_screenshot_sample_percent", 24, True),
        ),
    ),
    (
        "RuntimeResourcesAdjustedEvent",
        (
            ("old_extension_cpu_weight", 21, True),
            ("new_extension_cpu_weight", 22, True),
            ("old_media_encoder_slots", 23, True),
            ("new_media_encoder_slots", 24, True),
            ("old_freeze_background_tabs", 25, True),
            ("new_freeze_background_tabs", 26, True),
            ("old_block_new_tabs", 27, True),
            ("new_block_new_tabs", 28, True),
            ("old_extension_background_policy", 29, False),
            ("new_extension_background_policy", 30, False),
            ("old_success_trace_sample_percent", 31, True),
            ("new_success_trace_sample_percent", 32, True),
            ("old_observer_frame_rate_fps", 33, True),
            ("new_observer_frame_rate_fps", 34, True),
            ("old_video_recording_enabled", 35, True),
            ("new_video_recording_enabled", 36, True),
            ("old_success_screenshot_sample_percent", 37, True),
            ("new_success_screenshot_sample_percent", 38, True),
        ),
    ),
):
    message = proto.split(f"message {message_name} {{", 1)[1].split("}", 1)[0]
    message_tags = {
        name: (qualifier or "", int(tag))
        for qualifier, name, tag in re.findall(
            r"^\s*((?:optional|repeated)\s+)?[A-Za-z0-9_.]+\s+([a-z0-9_]+)\s*=\s*(\d+);",
            message,
            flags=re.MULTILINE,
        )
    }
    for name, expected_tag, must_be_optional in fields:
        qualifier, actual_tag = message_tags[name]
        assert actual_tag == expected_tag
        if must_be_optional:
            assert qualifier.strip() == "optional"

evidence_event = proto.split("message SessionEvidenceCapturedEvent {", 1)[1].split("}", 1)[0]
evidence_event_tags = {
    name: int(tag)
    for name, tag in re.findall(
        r"^\s*[A-Za-z0-9_.]+\s+([a-z0-9_]+)\s*=\s*(\d+);",
        evidence_event,
        flags=re.MULTILINE,
    )
}
assert evidence_event_tags == {
    "session_id": 1,
    "evidence_id": 2,
    "evidence_kind": 3,
    "task_id": 4,
    "step_id": 5,
    "command_id": 6,
    "content_sha256": 7,
    "content_bytes": 8,
    "object_key": 9,
    "captured_at_ms": 10,
    "mandatory": 11,
    "result": 12,
    "error_code": 13,
    "redaction_state": 14,
    "redacted_region_count": 15,
}

evidence_redaction_migration = read(
    "database/migrations/V063__session_evidence_sensitive_redaction.sql"
)
evidence_redaction_upper = evidence_redaction_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in evidence_redaction_upper, (
        f"evidence redaction migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "ADD COLUMN REDACTION_STATE TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'",
    "ADD COLUMN REDACTED_REGION_COUNT INTEGER NOT NULL DEFAULT 0",
    "REDACTION_STATE = 'LEGACY_UNVERIFIED'",
    "RESULT = 'COMMITTED'",
    "REDACTION_STATE = 'MASKED'",
    "REDACTION_STATE = 'NOT_REQUIRED'",
    "RESULT = 'FAILED'",
    "REDACTION_STATE = 'FAILED_CLOSED'",
    "VALIDATE CONSTRAINT CHK_SESSION_EVIDENCE_REDACTION_RESULT",
):
    assert invariant in evidence_redaction_upper, (
        f"evidence redaction migration lacks rolling invariant: {invariant}"
    )

notification_migration = read(
    "database/migrations/V064__workspace_notification_center.sql"
)
notification_upper = notification_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in notification_upper, (
        f"workspace notification migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE WORKSPACE_NOTIFICATIONS",
    "CREATE TABLE WORKSPACE_NOTIFICATION_READ_CURSORS",
    "UNIQUE (TENANT_ID, AUDIT_SEQUENCE_NO)",
    "CHECK (AUDIT_SEQUENCE_NO > 0) NOT VALID",
    "CHECK (CATEGORY IN ('SECURITY', 'RESOURCE', 'AGENT', 'RELEASE', 'SYSTEM')) NOT VALID",
    "CHECK (SEVERITY IN ('INFO', 'WARNING', 'CRITICAL')) NOT VALID",
    "VALIDATE CONSTRAINT CHK_WORKSPACE_NOTIFICATION_SEQUENCE",
    "VALIDATE CONSTRAINT CHK_WORKSPACE_NOTIFICATION_CATEGORY",
    "VALIDATE CONSTRAINT CHK_WORKSPACE_NOTIFICATION_SEVERITY",
    "CREATE TRIGGER APPEND_WORKSPACE_NOTIFICATION",
    "AFTER INSERT ON AUDIT_EVENTS",
    "NEW.CREATED_AT + INTERVAL '90 DAYS'",
):
    assert invariant in notification_upper, (
        f"workspace notification migration lacks rolling invariant: {invariant}"
    )
assert "INSERT INTO AUDIT_EVENTS" not in notification_upper

user_preference_migration = read(
    "database/migrations/V065__workspace_user_theme_preferences.sql"
)
user_preference_upper = user_preference_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in user_preference_upper, (
        f"user preference migration contains incompatible operation: {forbidden}"
    )
for invariant in (
    "CREATE TABLE WORKSPACE_USER_PREFERENCES",
    "PRIMARY KEY (TENANT_ID, ACTOR_ID)",
    "CHECK (THEME_MODE IN ('SYSTEM', 'DARK', 'LIGHT')) NOT VALID",
    "VALIDATE CONSTRAINT CHK_WORKSPACE_USER_PREFERENCE_THEME",
):
    assert invariant in user_preference_upper, (
        f"user preference migration lacks rolling invariant: {invariant}"
    )
assert "INSERT INTO WORKSPACE_USER_PREFERENCES" not in user_preference_upper

openapi = read("packages/contracts/openapi/session-api.yaml")
for evidence_path in (
    "/sessions/{sessionId}/evidence:capture:",
    "/sessions/{sessionId}/evidence-captures/{captureId}:",
    "/sessions/{sessionId}/evidence/{evidenceId}/access-grants:",
    "/sessions/{sessionId}/evidence-access-grants/{grantId}:redeem:",
):
    assert evidence_path in openapi
for notification_contract in (
    "/api/v1/notifications:",
    "/api/v1/notifications/read-cursor:",
    "WorkspaceNotificationListResponse:",
    "WorkspaceNotificationReadState:",
):
    assert notification_contract in openapi
for user_preference_contract in (
    "/api/v1/user-preferences:",
    "ThemeMode:",
    "UpdateUserPreferencesRequest:",
    "UserPreferences:",
):
    assert user_preference_contract in openapi
register = openapi.split("    RegisterBrowserNodeRequest:", 1)[1].split(
    "    RecordNodePressureRequest:", 1
)[0]
required = register.split("      properties:", 1)[0]
assert "certifiedMediaSlots" not in required
assert "supportsMedia" not in required
create = openapi.split("    CreateSessionRequest:", 1)[1].split(
    "    CreateSessionResponse:", 1
)[0]
create_required = create.split("      properties:", 1)[0]
for optional in (
    "applicationId",
    "runtimeBuildId",
    "humanTakeoverEnabled",
    "agentPolicy",
    "mediaWorkload",
    "requestedMediaStreams",
    "mediaBitrateKbps",
    "videoRecording",
    "proxyBindingProfileId",
):
    assert optional not in create_required
assert "resourceClass" not in create
assert "ResourceClass" not in openapi
assert "enum: [L0, L1, L2, L3, L4, L5]" not in openapi
assert "/api/v1/sessions/{sessionId}/application-binding:" in openapi
assert "/api/v1/sessions/{sessionId}/application-binding:rebind:" in openapi
assert "SessionApplicationBinding:" in openapi
assert "SessionApplicationRebind:" in openapi
assert "/api/v1/applications/{applicationId}/recovery-contract/revisions:" in openapi
assert (
    "/api/v1/applications/{applicationId}/recovery-contract/revisions/{version}/diff:"
    in openapi
)
assert "/api/v1/applications/{applicationId}/recovery-contract:restore:" in openapi
assert "RecoveryContractRevisionListResponse:" in openapi
assert "RecoveryContractDiff:" in openapi
assert "RestoreRecoveryContractRevisionRequest:" in openapi
assert "/api/v1/sessions/{sessionId}/business-recovery/provider-evidence:" in openapi
assert "ProviderEvidenceRequirement:" in openapi
assert "SubmitProviderEvidenceRequest:" in openapi
assert "ProviderEvidenceListResponse:" in openapi
assert "/api/v1/environment-saved-views:" in openapi
assert "/api/v1/environment-saved-views/{savedViewId}:" in openapi
assert "CreateEnvironmentSavedViewRequest:" in openapi
assert "UpdateEnvironmentSavedViewRequest:" in openapi
assert "EnvironmentSavedViewListResponse:" in openapi
assert "EnvironmentSavedViewTagMatch:" in openapi
assert "tagIds:" in openapi
assert "tagMatch:" in openapi
assert "/api/v1/environment-imports:preview:" in openapi
assert "/api/v1/environment-imports/{importId}:commit:" in openapi
assert "PreviewEnvironmentImportRequest:" in openapi
assert "EnvironmentImportListResponse:" in openapi
assert "/api/v1/profile-imports:" in openapi
assert "/api/v1/profile-imports/{importId}:" in openapi
assert "ProfileImport:" in openapi
assert "ProfileImportListResponse:" in openapi
assert "/api/v1/proxy-bindings:" in openapi
assert "/api/v1/proxy-bindings/{bindingProfileId}:" in openapi
assert "ProxyBindingRequest:" in openapi
assert "ProxyBindingList:" in openapi
assert "/api/v1/sessions/{sessionId}/proxy-binding:rebind:" in openapi
assert "/api/v1/sessions/{sessionId}/proxy-rebind:" in openapi
assert "ProxyRebindRequest:" in openapi
assert "ProxyRebindOperation:" in openapi
assert "ProxyRebind:" in openapi
assert "/api/v1/search:" in openapi
assert "GlobalSearchResult:" in openapi
assert "GlobalSearchResponse:" in openapi
assert "/api/v1/workspace-batch-operations:" in openapi
assert "/api/v1/workspace-batch-operations/{batchOperationId}:" in openapi
assert "/api/v1/workspace-batch-operations/{batchOperationId}:cancel:" in openapi
assert "WorkspaceBatchOperation:" in openapi
assert "WorkspaceBatchOperationListResponse:" in openapi
environment_import_migration = read(
    "database/migrations/V054__environment_import_jobs.sql"
)
assert "CREATE TABLE environment_import_jobs" in environment_import_migration
assert "CREATE TABLE environment_import_items" in environment_import_migration
assert "DROP " not in environment_import_migration.upper()

proxy_binding_migration = read(
    "database/migrations/V057__reusable_proxy_binding_profiles.sql"
)
proxy_binding_upper = proxy_binding_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in proxy_binding_upper
for invariant in (
    "CREATE TABLE PROXY_BINDING_PROFILES",
    "CREATE TABLE SESSION_PROXY_BINDING_ASSIGNMENTS",
    "FOREIGN KEY (SESSION_ID, TENANT_ID)",
    "FOREIGN KEY (BINDING_PROFILE_ID, TENANT_ID)",
    "ON DELETE RESTRICT NOT VALID",
    "ADD COLUMN BINDING_PROFILE_ID TEXT",
    "ADD COLUMN BINDING_VERSION BIGINT",
    "ADD COLUMN EXPECTED_EXIT_IP TEXT",
    "VALIDATE CONSTRAINT PROXY_ALLOCATIONS_BINDING_PROFILE_FK",
):
    assert invariant in proxy_binding_upper

routed_command_migration = read(
    "database/migrations/V058__routed_coordinator_command_inbox.sql"
)
routed_command_upper = routed_command_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in routed_command_upper
for invariant in (
    "CREATE TABLE COORDINATOR_COMMANDS",
    "UNIQUE (TENANT_ID, DEDUPLICATION_KEY)",
    "FOREIGN KEY (SESSION_ID, TENANT_ID)",
    "REFERENCES SESSIONS(ID, TENANT_ID)",
    "ON DELETE CASCADE NOT VALID",
    "'PENDING', 'EXECUTING', 'COMMITTED', 'FAILED'",
    "VALIDATE CONSTRAINT COORDINATOR_COMMANDS_SESSION_FK",
    "CREATE INDEX IDX_COORDINATOR_COMMANDS_READY",
):
    assert invariant in routed_command_upper

workspace_batch_migration = read(
    "database/migrations/V066__workspace_batch_operations.sql"
)
workspace_batch_upper = workspace_batch_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN"):
    assert forbidden not in workspace_batch_upper
for invariant in (
    "CREATE TABLE WORKSPACE_BATCH_OPERATIONS",
    "CREATE TABLE WORKSPACE_BATCH_OPERATION_ITEMS",
    "UNIQUE (TENANT_ID, IDEMPOTENCY_KEY)",
    "FOREIGN KEY (BATCH_OPERATION_ID, TENANT_ID)",
    "FOREIGN KEY (SESSION_ID, TENANT_ID)",
    "FOREIGN KEY (COMMAND_ID)",
    "REFERENCES COORDINATOR_COMMANDS(COMMAND_ID)",
    "CANCELLATION_REQUEST_HASH",
    "CANCELLATION_IDEMPOTENCY_KEY",
):
    assert invariant in workspace_batch_upper

workspace_metadata_batch_migration = read(
    "database/migrations/V068__workspace_metadata_batch_operations.sql"
)
workspace_metadata_batch_upper = workspace_metadata_batch_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in workspace_metadata_batch_upper
for invariant in (
    "CREATE TABLE WORKSPACE_METADATA_BATCH_OPERATIONS",
    "CREATE TABLE WORKSPACE_METADATA_BATCH_OPERATION_ITEMS",
    "'ASSIGN_GROUP', 'REMOVE_GROUP', 'ASSIGN_TAGS', 'REMOVE_TAGS'",
    "UNIQUE (TENANT_ID, IDEMPOTENCY_KEY)",
    "FOREIGN KEY (BATCH_OPERATION_ID, TENANT_ID)",
    "FOREIGN KEY (SESSION_ID, TENANT_ID)",
    "REFERENCES SESSIONS(ID, TENANT_ID)",
    "STATE IN ('ACCEPTED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'CANCELLED')",
    "ATTEMPT BETWEEN 0 AND 3",
    "CANCELLATION_REQUEST_HASH",
    "CANCELLATION_IDEMPOTENCY_KEY",
    "CREATE INDEX IDX_WORKSPACE_METADATA_BATCH_ITEMS_CLAIM",
):
    assert invariant in workspace_metadata_batch_upper

agent_task_summary_migration = read(
    "database/migrations/V069__agent_task_summary_cursor_index.sql"
)
agent_task_summary_upper = agent_task_summary_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in agent_task_summary_upper
for invariant in (
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_AGENT_TASKS_TENANT_SUMMARY_CURSOR",
    "ON AGENT_TASKS(TENANT_ID, CREATED_AT DESC, TASK_ID DESC)",
    "INCLUDE (STATE)",
):
    assert invariant in agent_task_summary_upper
agent_task_summary_config = read(
    "database/migrations/V069__agent_task_summary_cursor_index.sql.conf"
)
assert "executeInTransaction=false" in agent_task_summary_config

workspace_overview_migration = read(
    "database/migrations/V070__workspace_overview_stream.sql"
)
workspace_overview_upper = workspace_overview_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in workspace_overview_upper
for invariant in (
    "ADD CONSTRAINT CHK_AGENT_TASK_STATE_V2",
    "'PAUSED_BY_RESOURCE_POLICY'",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_AGENT_TASK_STATE_V2",
    "DROP CONSTRAINT CHK_AGENT_TASK_STATE",
    "RENAME CONSTRAINT CHK_AGENT_TASK_STATE_V2 TO CHK_AGENT_TASK_STATE",
    "CREATE TABLE WORKSPACE_OVERVIEW_EVENTS",
    "GENERATED ALWAYS AS IDENTITY PRIMARY KEY",
    "WHERE TENANT_ID IS NULL",
    "AFTER INSERT ON SESSION_EVENT_ENVELOPES",
    "AFTER INSERT ON BROWSER_NODES",
    "AFTER UPDATE ON BROWSER_NODES",
    "AFTER INSERT ON PROXY_ALLOCATIONS",
    "AFTER UPDATE ON PROXY_ALLOCATIONS",
    "AFTER INSERT ON SESSION_RESOURCE_COST_SNAPSHOTS",
    "AFTER INSERT ON WORKSPACE_NOTIFICATIONS",
):
    assert invariant in workspace_overview_upper, (
        f"Workspace Overview migration lacks rolling invariant: {invariant}"
    )

proxy_health_migration = read(
    "database/migrations/V071__proxy_active_health_quality.sql"
)
proxy_health_upper = proxy_health_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in proxy_health_upper
for invariant in (
    "CREATE TABLE PROXY_BINDING_HEALTH_SAMPLES",
    "SOURCE IN ('RUNTIME_BIND', 'ACTIVE_EXIT_PROBE')",
    "PROBE_SUCCESS_EWMA",
    "PROBE_LATENCY_EWMA_MS",
    "NOT VALID",
    "VALIDATE CONSTRAINT PROXY_BINDING_HEALTH_SAMPLES_PROFILE_FK",
    "AFTER UPDATE ON PROXY_BINDING_PROFILES",
    "INTERVAL '5 MINUTES'",
):
    assert invariant in proxy_health_upper, (
        f"Proxy health migration lacks rolling invariant: {invariant}"
    )

proxy_cold_probe_migration = read(
    "database/migrations/V072__proxy_binding_cold_probe.sql"
)
proxy_cold_probe_upper = proxy_cold_probe_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in proxy_cold_probe_upper
for invariant in (
    "ADD COLUMN NEXT_COLD_PROBE_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()",
    "ADD COLUMN COLD_PROBE_LEASE_OWNER TEXT",
    "ADD COLUMN COLD_PROBE_LEASE_UNTIL TIMESTAMPTZ",
    "ALTER COLUMN ALLOCATION_ID DROP NOT NULL",
    "ALTER COLUMN SESSION_ID DROP NOT NULL",
    "'COLD_BINDING_PROBE'",
    "CHK_PROXY_PROBE_CONTEXT",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_PROXY_PROBE_CONTEXT",
    "RESET_PROXY_BINDING_COLD_PROBE_AFTER_CONFIGURATION_CHANGE",
):
    assert invariant in proxy_cold_probe_upper, (
        f"Proxy cold probe migration lacks rolling invariant: {invariant}"
    )

proxy_routing_migration = read(
    "database/migrations/V073__proxy_provider_routing.sql"
)
proxy_routing_upper = proxy_routing_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in proxy_routing_upper
for invariant in (
    "ADD COLUMN COST_PER_GIB_USD NUMERIC(10, 4) NOT NULL DEFAULT 0",
    "ADD COLUMN REPUTATION_SCORE INTEGER NOT NULL DEFAULT 50",
    "ADD COLUMN MAX_CONCURRENT_SESSIONS INTEGER NOT NULL DEFAULT 10000",
    "ADD COLUMN SELECTION_MODE TEXT NOT NULL DEFAULT 'EXPLICIT'",
    "ADD COLUMN CANDIDATE_SCORES JSONB",
    "SELECTION_MODE IN ('EXPLICIT', 'AUTO')",
    "SESSION_PROXY_BINDING_ROUTING_SNAPSHOT_CHECK",
    "WHEN JSONB_TYPEOF(CANDIDATE_SCORES) = 'ARRAY' THEN CANDIDATE_SCORES",
    "NOT VALID",
    "VALIDATE CONSTRAINT SESSION_PROXY_BINDING_ROUTING_SNAPSHOT_CHECK",
    "IDX_SESSION_PROXY_BINDING_PROVIDER_RESERVATIONS",
):
    assert invariant in proxy_routing_upper, (
        f"Proxy routing migration lacks rolling invariant: {invariant}"
    )

business_recovery_readiness_migration = read(
    "database/migrations/V074__business_recovery_readiness_evidence.sql"
)
business_recovery_readiness_upper = business_recovery_readiness_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in business_recovery_readiness_upper
for invariant in (
    "ADD COLUMN REQUIRE_DOCUMENT_COMPLETE BOOLEAN NOT NULL DEFAULT FALSE",
    "ADD COLUMN MINIMUM_NETWORK_QUIET_MILLIS INTEGER NOT NULL DEFAULT 0",
    "ADD COLUMN TRANSIENT_BLOCKER_TARGETS JSONB NOT NULL DEFAULT '[]'",
    "APPLICATION_RECOVERY_CONTRACT_REVISIONS",
    "CREATE OR REPLACE FUNCTION SNAPSHOT_APPLICATION_RECOVERY_CONTRACT_REVISION",
    "NEW.REQUIRE_DOCUMENT_COMPLETE",
    "NEW.MINIMUM_NETWORK_QUIET_MILLIS",
    "NEW.TRANSIENT_BLOCKER_TARGETS",
):
    assert invariant in business_recovery_readiness_upper, (
        f"Business Recovery readiness migration lacks rolling invariant: {invariant}"
    )

release_freeze_migration = read(
    "database/migrations/V075__error_budget_release_freeze.sql"
)
release_freeze_upper = release_freeze_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE"):
    assert forbidden not in release_freeze_upper
for invariant in (
    "ADD COLUMN RELEASE_FREEZE_ENABLED BOOLEAN NOT NULL DEFAULT FALSE",
    "ADD COLUMN RELEASE_FREEZE_BURN_RATE_THRESHOLD NUMERIC(12,6) NOT NULL DEFAULT 1.000000",
    "ADD COLUMN RELEASE_RECOVERY_BURN_RATE_THRESHOLD NUMERIC(12,6) NOT NULL DEFAULT 0.500000",
    "CREATE TABLE ENTERPRISE_RELEASE_FREEZE_STATES",
    "INSERT INTO ENTERPRISE_RELEASE_FREEZE_STATES",
    "CREATE TABLE ENTERPRISE_RELEASE_FREEZE_EVENTS",
    "TRANSITION IN ('FROZEN', 'CLEARED')",
):
    assert invariant in release_freeze_upper, (
        f"Release freeze migration lacks rolling invariant: {invariant}"
    )

validation_worker_migration = read(
    "database/migrations/V076__runtime_validation_worker_queue.sql"
)
validation_worker_upper = validation_worker_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "ALTER COLUMN", "DROP TABLE", "ALTER TABLE"):
    assert forbidden not in validation_worker_upper
for invariant in (
    "CREATE TABLE RUNTIME_VALIDATION_JOBS",
    "REFERENCES RUNTIME_VALIDATION_RUNS(VALIDATION_ID)",
    "STATE IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'ACKED', 'COMMITTED', 'FAILED')",
    "CREATE INDEX IDX_RUNTIME_VALIDATION_JOBS_READY",
    "WHERE STATE = 'QUEUED'",
    "CREATE INDEX IDX_RUNTIME_VALIDATION_JOBS_LEASE",
    "CREATE TABLE RUNTIME_VALIDATION_JOB_EVENTS",
    "CREATE TABLE RUNTIME_VALIDATION_WORKERS",
):
    assert invariant in validation_worker_upper, (
        f"Runtime Validation Worker migration lacks rolling invariant: {invariant}"
    )

gameday_worker_migration = read(
    "database/migrations/V077__recovery_gameday_runner_queue.sql"
)
gameday_worker_upper = gameday_worker_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in gameday_worker_upper
for invariant in (
    "ADD COLUMN EXECUTION_MODE TEXT NOT NULL DEFAULT 'MANUAL'",
    "ADD COLUMN ENVIRONMENT TEXT NOT NULL DEFAULT 'TEST'",
    "STATE IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'ABORTED')",
    "CREATE TABLE RECOVERY_GAMEDAY_JOBS",
    "CREATE INDEX IDX_RECOVERY_GAMEDAY_JOBS_READY",
    "WHERE STATE IN ('QUEUED', 'RECOVERY_REQUIRED')",
    "CREATE INDEX IDX_RECOVERY_GAMEDAY_JOBS_LEASE",
    "CREATE TABLE RECOVERY_GAMEDAY_JOB_EVENTS",
    "CREATE TABLE RECOVERY_GAMEDAY_WORKERS",
):
    assert invariant in gameday_worker_upper, (
        f"Recovery GameDay Worker migration lacks rolling invariant: {invariant}"
    )

gameday_governance_migration = read(
    "database/migrations/V078__recovery_gameday_governance.sql"
)
gameday_governance_upper = gameday_governance_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER TABLE"):
    assert forbidden not in gameday_governance_upper
for invariant in (
    "CREATE INDEX IDX_RECOVERY_GAMEDAY_JOB_EVENTS_TIMELINE",
    "CREATE TABLE RECOVERY_GAMEDAY_REPORT_EXPORTS",
    "REPORT_HASH ~ '^[A-F0-9]{64}$'",
    "SIGNATURE_ALGORITHM = 'HMAC-SHA256'",
    "CREATE TABLE RECOVERY_GAMEDAY_REMEDIATION_TICKETS",
    "TEXT NOT NULL UNIQUE REFERENCES ENTERPRISE_RECOVERY_GAMEDAYS(GAMEDAY_ID)",
    "STATE IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')",
    "ON CONFLICT (GAMEDAY_ID) DO NOTHING",
):
    assert invariant in gameday_governance_upper, (
        f"Recovery GameDay governance migration lacks rolling invariant: {invariant}"
    )

agent_worker_migration = read(
    "database/migrations/V079__agent_execution_worker_queue.sql"
)
agent_worker_upper = agent_worker_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in agent_worker_upper
for invariant in (
    "ADD CONSTRAINT CHK_AGENT_TASK_STATE_V3",
    "'QUEUED'",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_AGENT_TASK_STATE_V3",
    "CREATE TABLE AGENT_EXECUTION_JOBS",
    "PROTOCOL_VERSION = 'AGENT-WORKER/V1'",
    "CLAIM_TOKEN_HASH ~ '^[A-F0-9]{64}$'",
    "CREATE INDEX IDX_AGENT_EXECUTION_JOBS_CLAIM",
    "CREATE INDEX IDX_AGENT_EXECUTION_JOBS_LEASE",
    "CREATE TABLE AGENT_EXECUTION_JOB_EVENTS",
):
    assert invariant in agent_worker_upper, (
        f"Agent Worker migration lacks rolling invariant: {invariant}"
    )

reviewer_worker_migration = read(
    "database/migrations/V080__agent_reviewer_worker_and_model_governance.sql"
)
reviewer_worker_upper = reviewer_worker_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in reviewer_worker_upper
for invariant in (
    "ADD COLUMN REVIEWER_STATUS TEXT NOT NULL DEFAULT 'NOT_REQUIRED'",
    "ADD CONSTRAINT CHK_AGENT_TASK_STATE_V4",
    "'AWAITING_REVIEW'",
    "NOT VALID",
    "VALIDATE CONSTRAINT CHK_AGENT_TASK_STATE_V4",
    "CREATE TABLE AGENT_REVIEW_JOBS",
    "PROTOCOL_VERSION = 'REVIEWER-WORKER/V1'",
    "CLAIM_TOKEN_HASH ~ '^[A-F0-9]{64}$'",
    "CREATE INDEX IDX_AGENT_REVIEW_JOBS_CLAIM",
    "CREATE INDEX IDX_AGENT_REVIEW_JOBS_LEASE",
    "CREATE TABLE AGENT_REVIEW_JOB_EVENTS",
    "INPUT_PRICE_MICROS_PER_MTOK",
    "OUTPUT_PRICE_MICROS_PER_MTOK",
    "MAXIMUM_OUTPUT_TOKENS",
):
    assert invariant in reviewer_worker_upper, (
        f"Reviewer Worker migration lacks rolling invariant: {invariant}"
    )

human_input_wait_migration = read(
    "database/migrations/V081__agent_human_input_wait_projection.sql"
)
human_input_wait_upper = human_input_wait_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in human_input_wait_upper
for invariant in (
    "ADD COLUMN EXECUTION_WAIT_REASON TEXT",
    "ADD COLUMN EXECUTION_WAIT_SINCE TIMESTAMPTZ",
    "EXECUTION_WAIT_REASON = 'HUMAN_INPUT_PRIORITY'",
    "CHK_AGENT_TASK_EXECUTION_WAIT_CONSISTENCY",
):
    assert invariant in human_input_wait_upper, (
        f"Agent human-input wait migration lacks rolling invariant: {invariant}"
    )

state_resync_budget_migration = read(
    "database/migrations/V082__state_resync_budget_and_circuit.sql"
)
state_resync_budget_upper = state_resync_budget_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in state_resync_budget_upper
for invariant in (
    "CREATE TABLE STATE_RESYNC_REQUESTS",
    "SOURCE IN ('USER', 'AUTOMATIC')",
    "MODE IN ('FULL', 'REGION')",
    "TOKEN_COST = 10",
    "TOKEN_COST = 2",
    "ROOT_REF_HASH",
    "IDX_STATE_RESYNC_REQUESTS_SESSION_BUDGET",
    "IDX_STATE_RESYNC_REQUESTS_TENANT_BUDGET",
    "IDX_STATE_RESYNC_REQUESTS_AUTOMATIC_CIRCUIT",
):
    assert invariant in state_resync_budget_upper, (
        f"State Resync budget migration lacks rolling invariant: {invariant}"
    )

state_snapshot_stream_migration = read(
    "database/migrations/V083__browser_state_snapshot_stream.sql"
)
state_snapshot_stream_upper = state_snapshot_stream_migration.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE"):
    assert forbidden not in state_snapshot_stream_upper
for invariant in (
    "CREATE TABLE BROWSER_STATE_SNAPSHOT_STREAMS",
    "CREATE TABLE BROWSER_STATE_SNAPSHOT_CHUNKS",
    "TOTAL_CHUNKS BETWEEN 1 AND 32",
    "TOTAL_BYTES BETWEEN 1 AND 524288",
    "CHUNK_BYTES BETWEEN 1 AND 16384",
    "PAYLOAD_SHA256",
    "COMMIT_RECEIVED",
    "ON DELETE CASCADE",
    "UQ_BROWSER_STATE_SNAPSHOT_ACTIVE_CONTEXT",
    "IDX_BROWSER_STATE_SNAPSHOT_EXPIRY",
):
    assert invariant in state_snapshot_stream_upper, (
        f"State snapshot stream migration lacks rolling invariant: {invariant}"
    )

state_resync_multidimensional_migration = read(
    "database/migrations/V084__state_resync_multidimensional_budget.sql"
)
state_resync_multidimensional_upper = state_resync_multidimensional_migration.upper()
state_resync_budget_indexes = read(
    "database/migrations/V085__state_resync_budget_online_indexes.sql"
)
state_resync_budget_indexes_upper = state_resync_budget_indexes.upper()
state_resync_budget_validation = read(
    "database/migrations/V086__state_resync_budget_validate_constraints.sql"
)
state_resync_budget_validation_upper = state_resync_budget_validation.upper()
for forbidden in ("DROP COLUMN", "RENAME COLUMN", "DROP TABLE", "ALTER COLUMN"):
    assert forbidden not in state_resync_multidimensional_upper
    assert forbidden not in state_resync_budget_indexes_upper
    assert forbidden not in state_resync_budget_validation_upper
for invariant in (
    "ADD COLUMN NODE_ID",
    "ADD COLUMN REGION",
    "ADD COLUMN RESERVED_BYTES",
    "ADD COLUMN ACTUAL_BYTES",
    "ADD COLUMN RESERVED_CPU_MILLIS",
    "ADD COLUMN ACTUAL_CPU_MILLIS",
    "ADD COLUMN BUDGET_STATE",
    "ADD COLUMN COLLECTION_CPU_MILLIS",
    "NOT VALID",
):
    assert invariant in state_resync_multidimensional_upper, (
        f"State Resync multi-dimensional migration lacks additive invariant: {invariant}"
    )
assert "UPDATE STATE_RESYNC_REQUESTS" not in state_resync_multidimensional_upper
for invariant in (
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_STATE_RESYNC_REQUESTS_REGION_BUDGET",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX_STATE_RESYNC_REQUESTS_NODE_BUDGET",
):
    assert invariant in state_resync_budget_indexes_upper
assert (
    read("database/migrations/V085__state_resync_budget_online_indexes.sql.conf").strip()
    == "executeInTransaction=false"
)
for constraint in (
    "CHK_STATE_RESYNC_REGION_WEIGHT",
    "CHK_STATE_RESYNC_ESTIMATED_BYTES",
    "CHK_STATE_RESYNC_RESERVED_BYTES",
    "CHK_STATE_RESYNC_ACTUAL_BYTES",
    "CHK_STATE_RESYNC_ESTIMATED_CPU",
    "CHK_STATE_RESYNC_RESERVED_CPU",
    "CHK_STATE_RESYNC_ACTUAL_CPU",
    "CHK_STATE_RESYNC_BUDGET_STATE",
    "CHK_STATE_RESYNC_SETTLEMENT",
    "CHK_BROWSER_STATE_SNAPSHOT_COLLECTION_CPU",
):
    assert f"VALIDATE CONSTRAINT {constraint}" in state_resync_budget_validation_upper

snapshot_begin_message = proto.split(
    "message BrowserStateSnapshotBeginEvent {", 1
)[1].split("}", 1)[0]
assert re.search(
    r"optional\s+uint64\s+collection_cpu_millis\s*=\s*9;", snapshot_begin_message
)
state_diff_message = proto.split("message BrowserStateDiffEvent {", 1)[1].split("}", 1)[0]
for field, tag in (("resync_request_id", 16), ("collection_cpu_millis", 17)):
    assert re.search(rf"\b{field}\s*=\s*{tag};", state_diff_message)

start_gameday_schema = openapi.split(
    "    StartRecoveryGameDayRequest:", 1
)[1].split("    RecoveryGameDayBlastRadius:", 1)[0]
start_gameday_required = start_gameday_schema.split("      properties:", 1)[0]
for optional_field in (
    "executionMode",
    "environment",
    "blastRadius",
    "maximumDurationSeconds",
    "approvalRequestId",
    "requiredWorkerCapabilities",
    "maximumAttempts",
):
    assert optional_field not in start_gameday_required

runtime_validation_schema = openapi.split(
    "    RuntimeValidation:", 1
)[1].split("    RuntimeValidationJob:", 1)[0]
runtime_validation_required = runtime_validation_schema.split("      properties:", 1)[0]
assert "job" not in runtime_validation_required
start_validation_schema = openapi.split(
    "    StartRuntimeValidationRequest:", 1
)[1].split("    RuntimeValidationMatrixCellRequest:", 1)[0]
start_validation_required = start_validation_schema.split("      properties:", 1)[0]
for optional_field in (
    "browserEngine",
    "browserVersion",
    "operatingSystem",
    "architecture",
    "requiredWorkerCapabilities",
    "maximumAttempts",
):
    assert optional_field not in start_validation_required

for message_name in ("BrowserStateEvent", "BrowserStateDiffEvent"):
    state_message = proto.split(f"message {message_name} {{", 1)[1].split("}", 1)[0]
    for field, tag in (
        ("document_ready_state", 11),
        ("network_quiet_millis", 12),
        ("network_evidence_fresh", 13),
    ):
        assert re.search(rf"\b{field}\s*=\s*{tag};", state_message), (
            f"{message_name} must keep additive Browser readiness tag {tag} for {field}"
        )

cold_probe_request = proto.split(
    "message ProbeProxyBindingRequest {", 1
)[1].split("}", 1)[0]
for field, tag in (
    ("probe_id", 1),
    ("tenant_id", 2),
    ("binding_profile_id", 3),
    ("provider_id", 4),
    ("expected_exit_ip", 5),
    ("credential_ref", 6),
):
    assert re.search(rf"\b{field}\s*=\s*{tag};", cold_probe_request)

cold_probe_response = proto.split(
    "message ProbeProxyBindingResponse {", 1
)[1].split("}", 1)[0]
for field, tag in (
    ("probe_id", 1),
    ("binding_profile_id", 2),
    ("node_id", 3),
    ("succeeded", 4),
    ("latency_ms", 5),
    ("observed_exit_ip", 6),
    ("error_code", 7),
):
    assert re.search(rf"\b{field}\s*=\s*{tag};", cold_probe_response)
assert "rpc ProbeProxyBinding(ProbeProxyBindingRequest) returns (ProbeProxyBindingResponse);" in proto

node_source = read("apps/browser-node/crates/node-agent/src/main.rs")
node_repository = read(
    "apps/control-plane/src/main/java/io/browsercloud/persistence/BrowserNodeJpaRepository.java"
)
cold_probe_store = read(
    "apps/control-plane/src/main/java/io/browsercloud/application/ProxyBindingColdProbeStore.java"
)
assert '"proxyColdProbe".to_owned()' in node_source
assert "labels->>'proxyColdProbe' = 'network-helper-v1'" in node_repository
assert "FOR UPDATE OF p SKIP LOCKED" in cold_probe_store

for metadata_batch_contract in (
    "/api/v1/workspace-metadata-batch-operations:",
    "/api/v1/workspace-metadata-batch-operations/{batchOperationId}:",
    "/api/v1/workspace-metadata-batch-operations/{batchOperationId}:cancel:",
    "CreateWorkspaceMetadataBatchOperationRequest:",
    "WorkspaceMetadataBatchOperation:",
    "WorkspaceMetadataBatchOperationListResponse:",
):
    assert metadata_batch_contract in openapi

for agent_summary_contract in (
    "/api/v1/agent-task-summaries:",
    "AgentTaskSummaryListResponse:",
    "AgentTaskSummaryMetrics:",
    "AgentTaskSummary:",
):
    assert agent_summary_contract in openapi

for workspace_overview_contract in (
    "/api/v1/workspace-overview:",
    "/api/v1/workspace-overview/event-stream:",
    "WorkspaceOverview:",
    "WorkspaceBrowserNodeSummary:",
    "WorkspaceAgentSummary:",
    "WorkspaceCostSummary:",
    "WorkspaceSecuritySummary:",
):
    assert workspace_overview_contract in openapi

for notification_stream_contract in (
    "/api/v1/notifications/event-stream:",
    "operationId: streamWorkspaceNotificationChanges",
    "Last-Event-ID",
    "text/event-stream",
):
    assert notification_stream_contract in openapi

# Additive SSE endpoint: an N-1 console keeps polling the unchanged list operation.
for audit_stream_contract in (
    "/api/v1/audit-events/event-stream:",
    "operationId: streamAuditEventChanges",
    "operationId: listAuditEvents",
):
    assert audit_stream_contract in openapi

for release_freeze_contract in (
    "/api/v1/enterprise/release-freeze:",
    "operationId: getReleaseFreezeState",
    "ReleaseFreeze:",
    "releaseFreezeEnabled:",
    "releaseFreezeBurnRateThreshold:",
    "releaseRecoveryBurnRateThreshold:",
):
    assert release_freeze_contract in openapi

slo_policy_request = openapi.split(
    "    UpsertSloPolicyRequest:", 1
)[1].split("    SloPolicy:", 1)[0]
slo_policy_required = slo_policy_request.split("      properties:", 1)[0]
for optional_field in (
    "releaseFreezeEnabled",
    "releaseFreezeBurnRateThreshold",
    "releaseRecoveryBurnRateThreshold",
    "releaseFreezeWindowMinutes",
    "releaseRecoveryStableMinutes",
):
    assert optional_field not in slo_policy_required

enterprise_overview_contract = openapi.split(
    "    EnterpriseOverview:", 1
)[1].split("\n    ", 1)[0]
enterprise_overview_required = enterprise_overview_contract.split(
    "      properties:", 1
)[0]
assert "releaseFreeze" not in enterprise_overview_required

recovery_contract_request = openapi.split(
    "    UpsertRecoveryContractRequest:", 1
)[1].split("    RecoveryContract:", 1)[0]
recovery_contract_required = recovery_contract_request.split(
    "      properties:", 1
)[0]
assert "recoveryAction" not in recovery_contract_required
assert "recoveryExtensionId" not in recovery_contract_required
assert "requiredProviderEvidence" not in recovery_contract_required

auto_recovery_command = proto.split(
    "message BusinessRecoveryActionCommand {", 1
)[1].split("}", 1)[0]
for field, tag in (
    ("session_id", 1),
    ("action_id", 2),
    ("action", 3),
    ("target_url", 4),
    ("base_state_version", 5),
    ("extension_id", 6),
):
    assert re.search(rf"\b{field}\s*=\s*{tag};", auto_recovery_command)

workloads = read("deploy/kubernetes/base/workloads.yaml")
assert "maxUnavailable: 0" in workloads
assert "maxSurge: 1" in workloads
assert "kind: PodDisruptionBudget" in workloads
assert "  maxUnavailable: 1" in workloads
assert "startupProbe:" in workloads
assert "readinessProbe:" in workloads
assert "kind: Deployment" in workloads
assert "COORDINATOR_INSTANCE_ID" in workloads
assert "fieldPath: metadata.name" in workloads

validation_worker_deployment = read(
    "deploy/kubernetes/base/validation-worker-deployment.yaml"
)
for invariant in (
    "runtimeClassName: validation-sandbox",
    "automountServiceAccountToken: false",
    "readOnlyRootFilesystem: true",
    "allowPrivilegeEscalation: false",
    "drop: [ALL]",
    "--control-plane-token-file=/var/run/browsercloud/identity/token",
    "--control-plane-ca-file=/var/run/browsercloud/ca/ca.crt",
    "--runner=/usr/local/bin/runtime-validation-runner",
):
    assert invariant in validation_worker_deployment

validation_worker_network_policy = read("deploy/kubernetes/base/network-policy.yaml")
for invariant in (
    "name: runtime-validation-worker-default-deny",
    "name: runtime-validation-worker-controlled-egress",
    'browsercloud.io/validation-fixtures: "true"',
):
    assert invariant in validation_worker_network_policy

agent_worker_deployment = read("deploy/kubernetes/base/agent-worker-deployment.yaml")
for invariant in (
    "runtimeClassName: agent-sandbox",
    "automountServiceAccountToken: false",
    "readOnlyRootFilesystem: true",
    "allowPrivilegeEscalation: false",
    "drop: [ALL]",
    "--control-plane-token-file=/var/run/browsercloud/identity/token",
    "--environment=production",
):
    assert invariant in agent_worker_deployment
for invariant in (
    "name: agent-worker-default-deny",
    "name: agent-worker-control-plane-only",
):
    assert invariant in validation_worker_network_policy
for invariant in (
    "/api/v1/agent-worker-jobs:claim:",
    "operationId: driveAgentExecutionJob",
    "ClaimAgentExecutionJobRequest:",
    "AgentExecutionJobClaim:",
    "const: agent-worker/v1",
):
    assert invariant in openapi

reviewer_worker_deployment = read(
    "deploy/kubernetes/base/reviewer-worker-deployment.yaml"
)
control_plane_workloads = read("deploy/kubernetes/base/workloads.yaml")
reviewer_feature_gate = control_plane_workloads.split(
    "- name: AGENT_REVIEWER_EXTERNAL_ENABLED", 1
)[1].split("- name: AGENT_REVIEWER_DEPLOYMENT_ID", 1)[0]
assert 'value: "false"' in reviewer_feature_gate
for invariant in (
    "runtimeClassName: agent-sandbox",
    "automountServiceAccountToken: false",
    "readOnlyRootFilesystem: true",
    "allowPrivilegeEscalation: false",
    "drop: [ALL]",
    'command: ["python", "/app/reviewer_worker.py"]',
    "--control-plane-token-file=/var/run/browsercloud/identity/token",
    "--model-api-key-file=/var/run/browsercloud/model/api-key",
    "--allowed-model-host=$(MODEL_PROVIDER_ALLOWED_HOST)",
    "--environment=production",
):
    assert invariant in reviewer_worker_deployment
for invariant in (
    "name: reviewer-worker-default-deny",
    "name: reviewer-worker-controlled-egress",
    'browsercloud.io/model-egress: "true"',
):
    assert invariant in validation_worker_network_policy
for invariant in (
    "/api/v1/agent-review-jobs:claim:",
    "operationId: completeAgentReviewJob",
    "ClaimAgentReviewJobRequest:",
    "AgentReviewJobClaim:",
    "const: reviewer-worker/v1",
    "AgentReview:",
):
    assert invariant in openapi
agent_task_schema = openapi.split("    AgentTask:", 1)[1].split(
    "    ClaimAgentExecutionJobRequest:", 1
)[0]
agent_task_required = agent_task_schema.split("      properties:", 1)[0]
assert "review" not in agent_task_required
assert "executionWait" not in agent_task_required
agent_task_summary_schema = openapi.split("    AgentTaskSummary:", 1)[1].split(
    "    AgentTask:", 1
)[0]
agent_task_summary_required = agent_task_summary_schema.split("      properties:", 1)[0]
assert "executionWaitReason" not in agent_task_summary_required
assert "executionWaitSince" not in agent_task_summary_required

gameday_worker_deployment = read(
    "deploy/kubernetes/base/gameday-worker-deployment.yaml"
)
for invariant in (
    "runtimeClassName: gameday-sandbox",
    "automountServiceAccountToken: false",
    "terminationGracePeriodSeconds: 45",
    "readOnlyRootFilesystem: true",
    "allowPrivilegeEscalation: false",
    "drop: [ALL]",
    "--control-plane-token-file=/var/run/browsercloud/identity/token",
    "--controller-token-file=/var/run/browsercloud/controller/token",
    "--runner=/usr/local/bin/gameday-runner",
    '{"version":1,"scenarios":{}}',
):
    assert invariant in gameday_worker_deployment
for invariant in (
    "name: recovery-gameday-worker-default-deny",
    "name: recovery-gameday-worker-controlled-egress",
    'browsercloud.io/gameday-controllers: "true"',
    'browsercloud.io/gameday-controller: "true"',
):
    assert invariant in validation_worker_network_policy

facts = {
    "schema": "V019-V021 additive,V028,V034,V039-V042,V062-V065,V070,V084-V086,V097 expand-online-index-validate,V098-site-policy-additive-and-validate,V099-recording-manifest-additive-and-validate,V100-secure-debug-notification-admission-function-replace,online concurrent-index,V029-V033,V035-V038,V043-V060,V066-V068,V071-V076 additive,V077 gameday-expand,V078 gameday-governance-additive,V079 agent-worker-expand,V080 reviewer-worker-expand,V081 agent-human-input-wait-expand,V082 state-resync-budget-additive,V083-state-snapshot-stream-additive,V093-workspace-desktop-actor-quota-additive-default-and-validate,V094-desktop-usage-metering-additive-default-and-validate,V095-profile-export-access-additive-and-validate,V096-profile-warm-tier-journal-additive-and-validate,V061 concurrent-trigram-index,V069 concurrent-agent-summary-index,V070 workspace-overview-stream",
    "protobuf": "unknown-fields-13-16,optional-28-38,browser-transaction-tags-35-37-capability-gated,browser-transaction-site-policy-start-tags-34-38,proxy-health-tags-31-34,resource-readback-tags-40-56,remote-desktop-usage-tags-10-12,cold-probe-rpc-request-1-6-response-1-7-capability-gated,extension-tags-15-22,media-slot-tags-16-24,tab-policy-tags-start-23-24-adjust-17-18-event-25-28,extension-background-tags-start-25-adjust-19-20-event-29-30,success-trace-tags-start-26-adjust-21-event-31-32,observer-fps-tags-start-27-adjust-22-event-33-34,recording-tags-start-28-adjust-23-event-35-36,recording-finalized-event-tags-1-14,screenshot-sampling-tags-start-29-adjust-24-event-37-38,start-minimum-browser-generation-tag-30,evidence-event-tags-1-15,recovery-extension-tag-6,browser-readiness-tags-full-and-diff-11-13,state-snapshot-begin-chunk-commit-additive,resync-request-and-cpu-tags-9-16-17,profile-import-stream-tags-1-10-capability-gated,evidence-presign-tags-request-1-8-response-1-5,profile-export-presign-tags-request-1-5-response-1-8-capability-gated,warm-tier-sync-event-tags-1-13,observer-capture-tags-1-2",
    "json": "AUTO-create-without-resource-class,public-resource-template-pricing,new-media-recording-and-application-recovery-fields-optional,recoveryExtensionId-and-approval-metadata-optional,profile-import-and-proxy-binding-additive-endpoints,proxy-provider-routing-metadata,workspace-batch-operation-saved-view-filter-and-metadata-batch-and-agent-summary-and-workspace-overview-and-notification-stream-and-audit-stream-and-release-freeze-and-validation-worker-and-gameday-worker-and-gameday-governance-and-agent-worker-and-reviewer-worker-and-human-input-wait-additive-contracts",
    "rolling": "leased-rendezvous-shard-dispatch,durable-routed-coordinator-command-inbox,durable-workspace-batch-command-ledger,isolated-metadata-batch-lease-ledger,isolated-validation-worker-lease-and-claim-token-fencing,isolated-gameday-worker-lease-claim-token-and-recovery-fencing,isolated-agent-worker-lease-claim-token-and-epoch-fencing,isolated-reviewer-worker-lease-claim-token-model-revision-and-plan-hash-fencing,proxy-cold-probe-db-lease-and-node-capability,proxy-routing-snapshot-and-fail-closed-selection,migration-target-generation-floor-capability,recording-frame-redaction-capability,migration-target-cleanup-gated-retry,maxUnavailable=0,maxSurge=1,pdb-maxUnavailable=1",
}
evidence = json.dumps(facts, sort_keys=True, separators=(",", ":")).encode()
print(
    json.dumps(
        {
            "status": "PASS",
            **facts,
            "evidenceHash": hashlib.sha256(evidence).hexdigest(),
        },
        sort_keys=True,
    )
)
