#!/usr/bin/env python3
"""Normalize generated TypeScript imports for standards-compliant Node ESM output."""

from __future__ import annotations

import pathlib
import re
import sys


RELATIVE_SPECIFIER = re.compile(
    r"(?P<prefix>\bfrom\s+|\bimport\s*)"
    r"(?P<quote>['\"])(?P<path>\.{1,2}/[^'\"]+)(?P=quote)"
)


def normalize_source(source: str) -> str:
    def replace(match: re.Match[str]) -> str:
        path = match.group("path")
        if pathlib.PurePosixPath(path).suffix:
            return match.group(0)
        return f"{match.group('prefix')}{match.group('quote')}{path}.js{match.group('quote')}"

    return RELATIVE_SPECIFIER.sub(replace, source)


def replace_exact(source: str, old: str, new: str, path: pathlib.Path) -> str:
    if source.count(old) != 1:
        raise SystemExit(f"generated SDK template changed unexpectedly: {path}: {old!r}")
    return source.replace(old, new)


def add_fetch_injection(path: pathlib.Path, source: str) -> str:
    relative = path.as_posix()
    if relative.endswith("/core/OpenAPI.ts"):
        source = replace_exact(
            source,
            "    ENCODE_PATH?: ((path: string) => string) | undefined;\n};",
            "    ENCODE_PATH?: ((path: string) => string) | undefined;\n"
            "    FETCH?: typeof fetch | undefined;\n};",
            path,
        )
        return replace_exact(
            source,
            "    ENCODE_PATH: undefined,\n};",
            "    ENCODE_PATH: undefined,\n    FETCH: undefined,\n};",
            path,
        )
    if relative.endswith("/BrowserCloudGeneratedClient.ts"):
        return replace_exact(
            source,
            "            ENCODE_PATH: config?.ENCODE_PATH,\n",
            "            ENCODE_PATH: config?.ENCODE_PATH,\n"
            "            FETCH: config?.FETCH,\n",
            path,
        )
    if relative.endswith("/core/request.ts"):
        return replace_exact(
            source,
            "    return await fetch(url, request);",
            "    return await (config.FETCH ?? fetch)(url, request);",
            path,
        )
    return source


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: normalize_typescript_sdk.py GENERATED_DIRECTORY")
    generated = pathlib.Path(sys.argv[1]).resolve()
    if not generated.is_dir():
        raise SystemExit(f"generated SDK directory does not exist: {generated}")
    sources = sorted(generated.rglob("*.ts"))
    if not sources:
        raise SystemExit("generated SDK contains no TypeScript sources")
    for path in sources:
        original = path.read_text(encoding="utf-8")
        normalized = add_fetch_injection(path, normalize_source(original)).rstrip() + "\n"
        if normalized != original:
            path.write_text(normalized, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
