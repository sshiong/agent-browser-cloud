#!/usr/bin/env python3
"""Build and verify deterministic Python, Go and Java SDK release artifacts."""

from __future__ import annotations

import base64
import csv
import hashlib
import io
import json
import pathlib
import shutil
import subprocess
import sys
import tarfile
import zipfile


FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def fail(message: str) -> None:
    raise SystemExit(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def wheel_digest(data: bytes) -> str:
    return "sha256=" + base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()


def zip_write(archive: zipfile.ZipFile, name: str, data: bytes, executable: bool = False) -> None:
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = ((0o755 if executable else 0o644) & 0xFFFF) << 16
    archive.writestr(info, data)


def deterministic_zip(path: pathlib.Path, entries: list[tuple[str, bytes]]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in sorted(entries):
            zip_write(archive, name, data)


def build_wheel(root: pathlib.Path, output: pathlib.Path, version: str) -> pathlib.Path:
    normalized = version.replace("-", "_")
    wheel = output / f"agent_browser_cloud-{normalized}-py3-none-any.whl"
    entries: list[tuple[str, bytes]] = []
    package = root / "sdks/python/browsercloud"
    for source in sorted(package.glob("*.py")):
        entries.append((f"browsercloud/{source.name}", source.read_bytes()))
    dist = f"agent_browser_cloud-{normalized}.dist-info"
    metadata = (
        "Metadata-Version: 2.3\n"
        "Name: agent-browser-cloud\n"
        f"Version: {version}\n"
        "Summary: OpenAPI-generated Agent Browser Cloud Python SDK\n"
        "Requires-Python: >=3.10\n"
        "License: UNLICENSED\n\n"
    ).encode()
    wheel_metadata = (
        "Wheel-Version: 1.0\nGenerator: browsercloud-release-builder@1\n"
        "Root-Is-Purelib: true\nTag: py3-none-any\n\n"
    ).encode()
    entries.extend([(f"{dist}/METADATA", metadata), (f"{dist}/WHEEL", wheel_metadata)])
    record_rows = [[name, wheel_digest(data), str(len(data))] for name, data in sorted(entries)]
    record_rows.append([f"{dist}/RECORD", "", ""])
    buffer = io.StringIO(newline="")
    csv.writer(buffer, lineterminator="\n").writerows(record_rows)
    entries.append((f"{dist}/RECORD", buffer.getvalue().encode()))
    deterministic_zip(wheel, entries)
    return wheel


def build_go_module(root: pathlib.Path, output: pathlib.Path, version: str) -> list[pathlib.Path]:
    module = "github.com/ricardohoye/agent-browser-cloud/sdks/go"
    prefix = f"{module}@v{version}/"
    go_root = root / "sdks/go"
    sources = [
        source
        for source in go_root.rglob("*")
        if source.is_file() and "build" not in source.parts and not source.name.endswith("_test.go")
    ]
    module_zip = output / f"agent-browser-cloud-go-v{version}.zip"
    deterministic_zip(
        module_zip,
        [(prefix + source.relative_to(go_root).as_posix(), source.read_bytes()) for source in sources],
    )
    mod = output / f"agent-browser-cloud-go-v{version}.mod"
    mod.write_bytes((go_root / "go.mod").read_bytes())
    info = output / f"agent-browser-cloud-go-v{version}.info"
    info.write_text(json.dumps({"Version": f"v{version}"}, separators=(",", ":")) + "\n")
    return [module_zip, mod, info]


def compile_java(root: pathlib.Path, classes: pathlib.Path) -> None:
    sources = sorted((root / "sdks/java/src/main/java").rglob("*.java"))
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["javac", "--release", "17", "-d", str(classes), *map(str, sources)], check=True
    )


def build_java(root: pathlib.Path, output: pathlib.Path, version: str) -> list[pathlib.Path]:
    classes = root / "build/sdk/java-release-classes"
    compile_java(root, classes)
    manifest = (
        "Manifest-Version: 1.0\r\n"
        "Automatic-Module-Name: io.browsercloud.sdk\r\n"
        "Created-By: browsercloud-release-builder@1\r\n\r\n"
    ).encode()
    jar = output / f"agent-browser-cloud-sdk-{version}.jar"
    class_entries = [("META-INF/MANIFEST.MF", manifest)] + [
        (source.relative_to(classes).as_posix(), source.read_bytes())
        for source in classes.rglob("*.class")
    ]
    deterministic_zip(jar, class_entries)
    source_jar = output / f"agent-browser-cloud-sdk-{version}-sources.jar"
    source_root = root / "sdks/java/src/main/java"
    deterministic_zip(
        source_jar,
        [
            (source.relative_to(source_root).as_posix(), source.read_bytes())
            for source in source_root.rglob("*.java")
        ],
    )
    pom = output / f"agent-browser-cloud-sdk-{version}.pom"
    shutil.copyfile(root / "sdks/java/pom.xml", pom)
    return [jar, source_jar, pom]


def verify_artifacts(artifacts: list[pathlib.Path], version: str) -> None:
    wheel = next(path for path in artifacts if path.suffix == ".whl")
    with zipfile.ZipFile(wheel) as archive:
        names = set(archive.namelist())
        if "browsercloud/generated_client.py" not in names or "browsercloud/generated_models.py" not in names:
            fail("Python wheel is missing generated client or models")
        if any("tests/" in name or "__pycache__" in name for name in names):
            fail("Python wheel contains test or cache files")
    jar = next(path for path in artifacts if path.name == f"agent-browser-cloud-sdk-{version}.jar")
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        if not {
            "io/browsercloud/sdk/generated/BrowserCloudGeneratedClient.class",
            "io/browsercloud/sdk/generated/Models.class",
        }.issubset(names):
            fail("Java JAR is missing generated client or models")
    go_zip = next(path for path in artifacts if path.name.endswith(f"go-v{version}.zip"))
    with zipfile.ZipFile(go_zip) as archive:
        names = set(archive.namelist())
        if not any(name.endswith("/browsercloud/generated/client.gen.go") for name in names):
            fail("Go module zip is missing generated client")
        if any(name.endswith("_test.go") for name in names):
            fail("Go module zip contains tests")
    typescript = next(path for path in artifacts if path.name == f"browsercloud-sdk-{version}.tgz")
    with tarfile.open(typescript, "r:gz") as archive:
        names = set(archive.getnames())
        if not {
            "package/dist/generated/BrowserCloudGeneratedClient.js",
            "package/dist/generated/BrowserCloudGeneratedClient.d.ts",
        }.issubset(names):
            fail("TypeScript tarball is missing generated client runtime or types")
        if any("/src/" in name or "/test/" in name for name in names):
            fail("TypeScript tarball contains source or test files")


def main() -> int:
    if len(sys.argv) != 2:
        fail("usage: build_multilang_release.py REPOSITORY_ROOT")
    root = pathlib.Path(sys.argv[1]).resolve()
    version = (root / "sdks/VERSION").read_text().strip()
    if not version or not all(part.isdigit() for part in version.split(".")):
        fail("sdks/VERSION must be a numeric semantic version")
    if f'version = "{version}"' not in (root / "sdks/python/pyproject.toml").read_text():
        fail("Python package version is not aligned with sdks/VERSION")
    if f"<version>{version}</version>" not in (root / "sdks/java/pom.xml").read_text():
        fail("Java package version is not aligned with sdks/VERSION")
    output = root / "build/sdk-release"
    output.mkdir(parents=True, exist_ok=True)
    expected_names = {
        f"agent_browser_cloud-{version}-py3-none-any.whl",
        f"agent-browser-cloud-go-v{version}.zip",
        f"agent-browser-cloud-go-v{version}.mod",
        f"agent-browser-cloud-go-v{version}.info",
        f"agent-browser-cloud-sdk-{version}.jar",
        f"agent-browser-cloud-sdk-{version}-sources.jar",
        f"agent-browser-cloud-sdk-{version}.pom",
        f"browsercloud-sdk-{version}.tgz",
    }
    typescript = output / f"browsercloud-sdk-{version}.tgz"
    if not typescript.is_file():
        fail("TypeScript tarball must be built before the multi-language release manifest")
    typescript_payload = typescript.read_bytes()
    for child in output.iterdir():
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()
    typescript.write_bytes(typescript_payload)
    artifacts = [typescript, build_wheel(root, output, version)]
    artifacts.extend(build_go_module(root, output, version))
    artifacts.extend(build_java(root, output, version))
    if {path.name for path in artifacts} != expected_names:
        fail("release artifact set drifted")
    verify_artifacts(artifacts, version)
    checksums = "".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
        for path in sorted(artifacts)
    )
    (output / "SHA256SUMS").write_text(checksums)
    release = {
        "formatVersion": 1,
        "version": version,
        "contractSha256": json.loads(
            (root / "sdks/generated-multilang-manifest.json").read_text()
        )["contractSha256"],
        "gitCommit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True
        ).strip(),
        "artifacts": {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(artifacts)
        },
    }
    (output / "release-manifest.json").write_text(
        json.dumps(release, indent=2, sort_keys=True) + "\n"
    )
    print(f"multilang_sdk_release=true version={version} artifacts={len(artifacts)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
