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

proto = read("packages/contracts/proto/node/v1/node_command.proto")
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
for optional in ("mediaWorkload", "requestedMediaStreams", "mediaBitrateKbps"):
    assert optional not in create_required

workloads = read("deploy/kubernetes/base/workloads.yaml")
assert "maxUnavailable: 0" in workloads
assert "maxSurge: 1" in workloads
assert "kind: PodDisruptionBudget" in workloads
assert "  maxUnavailable: 1" in workloads
assert "startupProbe:" in workloads
assert "readinessProbe:" in workloads

facts = {
    "schema": "V019-V021 additive,V028 expand-validate-contract,V029 additive-safety-stream",
    "protobuf": "unknown-fields-15-16,optional-28-30",
    "json": "new-media-fields-optional",
    "rolling": "maxUnavailable=0,maxSurge=1,pdb-maxUnavailable=1",
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
