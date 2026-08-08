#!/usr/bin/env python3
"""Write a deterministic manifest binding generated SDK sources to the OpenAPI contract."""

from __future__ import annotations

import hashlib
import json
import pathlib
import sys


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    if len(sys.argv) != 5:
        raise SystemExit(
            "usage: write_typescript_sdk_manifest.py OPENAPI GENERATED PACKAGE_JSON OUTPUT"
        )
    contract = pathlib.Path(sys.argv[1]).resolve()
    generated = pathlib.Path(sys.argv[2]).resolve()
    package_json = pathlib.Path(sys.argv[3]).resolve()
    output = pathlib.Path(sys.argv[4]).resolve()
    package = json.loads(package_json.read_text(encoding="utf-8"))
    generator_version = package["devDependencies"]["openapi-typescript-codegen"]
    sources = sorted(generated.rglob("*.ts"))
    manifest = {
        "formatVersion": 1,
        "generator": f"openapi-typescript-codegen@{generator_version}",
        "contract": contract.name,
        "contractSha256": sha256(contract),
        "files": {
            source.relative_to(generated).as_posix(): sha256(source) for source in sources
        },
    }
    output.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
