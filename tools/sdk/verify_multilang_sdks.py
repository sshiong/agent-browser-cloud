#!/usr/bin/env python3
"""Verify exact OpenAPI coverage and source integrity for generated Python/Go/Java SDKs."""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import sys


PYTHON_METHOD = re.compile(r"^    def ([A-Za-z][A-Za-z0-9_]*)\(self, \*", re.MULTILINE)
GO_METHOD = re.compile(
    r"^func \(c \*Client\) ([A-Za-z][A-Za-z0-9_]*)\(ctx context.Context, request Request\)",
    re.MULTILINE,
)
JAVA_METHOD = re.compile(
    r"^  public Response ([A-Za-z][A-Za-z0-9_]*)\(Request request\)", re.MULTILINE
)
PYTHON_MODEL = re.compile(
    r"^(?:class ([A-Za-z][A-Za-z0-9_]*)\(TypedDict|([A-Za-z][A-Za-z0-9_]*) = Literal\[)",
    re.MULTILINE,
)
GO_MODEL = re.compile(r"^type ([A-Za-z][A-Za-z0-9_]*) (?:struct|string)", re.MULTILINE)
JAVA_MODEL = re.compile(
    r"^  public (?:record|enum) ([A-Za-z][A-Za-z0-9_]*)", re.MULTILINE
)


def fail(message: str) -> None:
    raise SystemExit(message)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def go_name(value: str) -> str:
    parts = re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+", value)
    return "".join(part[:1].upper() + part[1:] for part in parts) or "Value"


def operations(document: dict) -> set[str]:
    methods = {"get", "post", "put", "patch", "delete", "options", "head", "trace"}
    result = {
        operation["operationId"]
        for path_item in document["paths"].values()
        for method, operation in path_item.items()
        if method in methods
    }
    if not result:
        fail("OpenAPI contract has no operations")
    return result


def main() -> int:
    if len(sys.argv) != 4:
        fail("usage: verify_multilang_sdks.py OPENAPI_JSON OPENAPI_YAML REPOSITORY_ROOT")
    bundled = pathlib.Path(sys.argv[1]).resolve()
    contract = pathlib.Path(sys.argv[2]).resolve()
    root = pathlib.Path(sys.argv[3]).resolve()
    sdk_root = root / "sdks"
    document = json.loads(bundled.read_text(encoding="utf-8"))
    expected_operations = operations(document)
    expected_schemas = set(document["components"]["schemas"])
    manifest_path = sdk_root / "generated-multilang-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_files = {
        "python/browsercloud/generated_client.py",
        "python/browsercloud/generated_models.py",
        "go/browsercloud/generated/client.gen.go",
        "go/browsercloud/generated/models.gen.go",
        "java/src/main/java/io/browsercloud/sdk/generated/BrowserCloudGeneratedClient.java",
        "java/src/main/java/io/browsercloud/sdk/generated/Models.java",
    }
    if manifest.get("formatVersion") != 1:
        fail("unsupported generated multi-language SDK manifest")
    if manifest.get("contractSha256") != sha256(contract):
        fail("generated multi-language SDK is not bound to the current OpenAPI contract")
    if manifest.get("operationCount") != len(expected_operations):
        fail("generated multi-language SDK operation count drifted")
    if manifest.get("schemaCount") != len(expected_schemas):
        fail("generated multi-language SDK schema count drifted")
    actual_hashes = {
        relative: sha256(sdk_root / relative) for relative in sorted(expected_files)
    }
    if manifest.get("files") != actual_hashes:
        fail("generated multi-language SDK source hash drifted")

    python_client = (sdk_root / "python/browsercloud/generated_client.py").read_text()
    go_client = (sdk_root / "go/browsercloud/generated/client.gen.go").read_text()
    java_client = (
        sdk_root
        / "java/src/main/java/io/browsercloud/sdk/generated/BrowserCloudGeneratedClient.java"
    ).read_text()
    client_operations = {
        "python": set(PYTHON_METHOD.findall(python_client)),
        "go": set(GO_METHOD.findall(go_client)),
        "java": set(JAVA_METHOD.findall(java_client)),
    }
    expected_by_language = {
        "python": expected_operations,
        "go": {go_name(value) for value in expected_operations},
        "java": expected_operations,
    }
    for language, actual in client_operations.items():
        if actual != expected_by_language[language]:
            fail(
                f"{language} generated operation drift: "
                f"missing={sorted(expected_by_language[language] - actual)} "
                f"extra={sorted(actual - expected_by_language[language])}"
            )

    python_models = (sdk_root / "python/browsercloud/generated_models.py").read_text()
    go_models = (sdk_root / "go/browsercloud/generated/models.gen.go").read_text()
    java_models = (
        sdk_root / "java/src/main/java/io/browsercloud/sdk/generated/Models.java"
    ).read_text()
    python_names = {
        first or second for first, second in PYTHON_MODEL.findall(python_models)
    }
    model_names = {
        "python": python_names,
        "go": set(GO_MODEL.findall(go_models)),
        "java": set(JAVA_MODEL.findall(java_models)),
    }
    for language, actual in model_names.items():
        if actual != expected_schemas:
            fail(
                f"{language} generated schema drift: "
                f"missing={sorted(expected_schemas - actual)} extra={sorted(actual - expected_schemas)}"
            )

    print(
        f"multilang_sdk_verified=true operations={len(expected_operations)} "
        f"schemas={len(expected_schemas)} languages=python,go,java"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
