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

proto = read("packages/contracts/proto/node/v1/node_command.proto")
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
):
    qualifier, actual_tag = resource_tags[name]
    assert qualifier is not None, f"{name} must remain optional for N/N-1"
    assert actual_tag == expected_tag

for message_name, fields in (
    (
        "StartRuntimeCommand",
        (
            ("extension_ids", 20, False),
            ("extension_cpu_weight", 21, True),
            ("media_encoder_slots", 22, True),
        ),
    ),
    (
        "AdjustRuntimeResourcesCommand",
        (("extension_cpu_weight", 15, True), ("media_encoder_slots", 16, True)),
    ),
    (
        "RuntimeResourcesAdjustedEvent",
        (
            ("old_extension_cpu_weight", 21, True),
            ("new_extension_cpu_weight", 22, True),
            ("old_media_encoder_slots", 23, True),
            ("new_media_encoder_slots", 24, True),
        ),
    ),
):
    message = proto.split(f"message {message_name} {{", 1)[1].split("}", 1)[0]
    message_tags = {
        name: (qualifier or "", int(tag))
        for qualifier, name, tag in re.findall(
            r"^\s*((?:optional|repeated)\s+)?[a-z0-9_]+\s+([a-z0-9_]+)\s*=\s*(\d+);",
            message,
            flags=re.MULTILINE,
        )
    }
    for name, expected_tag, must_be_optional in fields:
        qualifier, actual_tag = message_tags[name]
        assert actual_tag == expected_tag
        if must_be_optional:
            assert qualifier.strip() == "optional"

openapi = read("packages/contracts/openapi/session-api.yaml")
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
):
    assert optional not in create_required

recovery_contract_request = openapi.split(
    "    UpsertRecoveryContractRequest:", 1
)[1].split("    RecoveryContract:", 1)[0]
recovery_contract_required = recovery_contract_request.split(
    "      properties:", 1
)[0]
assert "recoveryAction" not in recovery_contract_required
assert "recoveryExtensionId" not in recovery_contract_required

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
    "schema": "V019-V021 additive,V028,V034,V039-V041 expand-validate-contract,online concurrent-index,V029-V033,V035-V038 additive",
    "protobuf": "unknown-fields-13-16,optional-28-30,extension-tags-15-22,media-slot-tags-16-24,recovery-extension-tag-6",
    "json": "new-media-and-application-recovery-fields-optional,recoveryExtensionId-optional",
    "rolling": "leased-rendezvous-shard-dispatch,maxUnavailable=0,maxSurge=1,pdb-maxUnavailable=1",
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
