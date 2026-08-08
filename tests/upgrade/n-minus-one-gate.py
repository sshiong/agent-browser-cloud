#!/usr/bin/env python3
"""Fail the release when additive N/N-1 compatibility invariants regress."""

import hashlib
import json
import pathlib
import re


ROOT = pathlib.Path(__file__).resolve().parents[2]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


enterprise_migrations = {
    version: read(f"database/migrations/{version}")
    for version in (
        "V019__enterprise_operations.sql",
        "V020__media_and_extension_sampling.sql",
        "V021__enterprise_governance_evidence.sql",
    )
}
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
    ("proxy_probe_succeeded", 31),
    ("proxy_probe_latency_ms", 32),
    ("proxy_observed_exit_ip", 33),
):
    qualifier, actual_tag = resource_tags[name]
    assert qualifier is not None, f"{name} must remain optional for N/N-1"
    assert actual_tag == expected_tag
assert resource_tags["proxy_probe_error_code"][1] == 34

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

facts = {
    "schema": "V019-V021 additive,V028,V034,V039-V042,V062-V065,V070 expand-validate-contract,online concurrent-index,V029-V033,V035-V038,V043-V060,V066-V068,V071 additive,V061 concurrent-trigram-index,V069 concurrent-agent-summary-index,V070 workspace-overview-stream",
    "protobuf": "unknown-fields-13-16,optional-28-38,proxy-health-tags-31-34,extension-tags-15-22,media-slot-tags-16-24,tab-policy-tags-start-23-24-adjust-17-18-event-25-28,extension-background-tags-start-25-adjust-19-20-event-29-30,success-trace-tags-start-26-adjust-21-event-31-32,observer-fps-tags-start-27-adjust-22-event-33-34,recording-tags-start-28-adjust-23-event-35-36,screenshot-sampling-tags-start-29-adjust-24-event-37-38,start-minimum-browser-generation-tag-30,evidence-event-tags-1-15,recovery-extension-tag-6,profile-import-stream-tags-1-10-capability-gated,evidence-presign-tags-request-1-8-response-1-5,observer-capture-tags-1-2",
    "json": "AUTO-create-without-resource-class,public-resource-template-pricing,new-media-recording-and-application-recovery-fields-optional,recoveryExtensionId-and-approval-metadata-optional,profile-import-and-proxy-binding-additive-endpoints,workspace-batch-operation-saved-view-filter-and-metadata-batch-and-agent-summary-and-workspace-overview-additive-contracts",
    "rolling": "leased-rendezvous-shard-dispatch,durable-routed-coordinator-command-inbox,durable-workspace-batch-command-ledger,isolated-metadata-batch-lease-ledger,migration-target-generation-floor-capability,migration-target-cleanup-gated-retry,maxUnavailable=0,maxSurge=1,pdb-maxUnavailable=1",
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
