#!/usr/bin/env python3
"""Prove that the generated TypeScript SDK covers every OpenAPI operation."""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import sys


OPERATION_ID = re.compile(r"^\s+operationId:\s*([A-Za-z][A-Za-z0-9_]*)\s*$", re.MULTILINE)
GENERATED_METHOD = re.compile(r"^\s+public\s+([A-Za-z][A-Za-z0-9_]*)\s*\(", re.MULTILINE)
RELATIVE_SPECIFIER = re.compile(
    r"(?:\bfrom\s+|\bimport\s*)['\"](?P<path>\.{1,2}/[^'\"]+)['\"]"
)


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> int:
    if len(sys.argv) != 4:
        fail("usage: verify_typescript_sdk.py OPENAPI_FILE GENERATED_DIRECTORY MANIFEST")
    contract = pathlib.Path(sys.argv[1]).resolve()
    generated = pathlib.Path(sys.argv[2]).resolve()
    manifest_path = pathlib.Path(sys.argv[3]).resolve()
    operation_ids = OPERATION_ID.findall(contract.read_text(encoding="utf-8"))
    if not operation_ids or len(operation_ids) != len(set(operation_ids)):
        fail("OpenAPI operationId values must be present and globally unique")

    service_sources = sorted((generated / "services").glob("*.ts"))
    generated_methods: list[str] = []
    for source in service_sources:
        body = source.read_text(encoding="utf-8")
        if "generated using openapi-typescript-codegen" not in body:
            fail(f"service is missing the generated-source banner: {source}")
        source_methods = GENERATED_METHOD.findall(body)
        if len(source_methods) != len(set(source_methods)):
            fail(f"generated service contains duplicate public methods: {source}")
        generated_methods.extend(source_methods)

    expected = set(operation_ids)
    actual = set(generated_methods)
    if expected != actual:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        fail(f"generated SDK operation drift: missing={missing}, extra={extra}")

    index = (generated / "index.ts").read_text(encoding="utf-8")
    client = (generated / "BrowserCloudGeneratedClient.ts").read_text(encoding="utf-8")
    if "BrowserCloudGeneratedClient" not in index or "class BrowserCloudGeneratedClient" not in client:
        fail("generated SDK does not expose an instance-scoped BrowserCloud client")

    for source in sorted(generated.rglob("*.ts")):
        for match in RELATIVE_SPECIFIER.finditer(source.read_text(encoding="utf-8")):
            if not pathlib.PurePosixPath(match.group("path")).suffix:
                fail(f"Node ESM import has no explicit extension: {source}: {match.group('path')}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_files = {
        source.relative_to(generated).as_posix(): hashlib.sha256(source.read_bytes()).hexdigest()
        for source in sorted(generated.rglob("*.ts"))
    }
    if manifest.get("formatVersion") != 1:
        fail("generated SDK manifest version is unsupported")
    if manifest.get("contractSha256") != hashlib.sha256(contract.read_bytes()).hexdigest():
        fail("generated SDK manifest is not bound to the current OpenAPI contract")
    if manifest.get("files") != expected_files:
        fail("generated SDK manifest does not match the exact generated source set")

    print(
        f"typescript_sdk_generated=true operations={len(operation_ids)} "
        f"service_methods={len(generated_methods)} services={len(service_sources)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
