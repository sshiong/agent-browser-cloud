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
    "schema": "V019-V021-expand-only-with-compatible-defaults",
    "protobuf": "unknown-fields-15-16",
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
