#!/usr/bin/env python3
"""Render and verify a digest-locked production deployment bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path


SCHEMA_VERSION = 3
COMPONENTS = {
    "control-plane": "ghcr.io/sshiong/agent-browser-cloud-control-plane",
    "browser-node": "ghcr.io/sshiong/agent-browser-cloud-browser-node",
    "web-console": "ghcr.io/sshiong/agent-browser-cloud-web-console",
    "operator": "ghcr.io/sshiong/agent-browser-cloud-operator",
    "application-adapter": "ghcr.io/sshiong/agent-browser-cloud-application-adapter",
    "validation-worker": "ghcr.io/sshiong/agent-browser-cloud-validation-worker",
}
REFERENCE_PATTERN = re.compile(
    r"^(?P<repository>[a-z0-9.-]+(?::[0-9]+)?/[a-z0-9._/-]+)"
    r"@(?P<digest>sha256:[a-f0-9]{64})$"
)
COMMIT_PATTERN = re.compile(r"^[a-f0-9]{40}$")
SPDX_MEDIA_TYPE = "application/spdx+json"


class BundleError(ValueError):
    """A release bundle failed a security invariant."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(64 * 1024), b""):
            digest.update(block)
    return f"sha256:{digest.hexdigest()}"


def parse_images(values: list[str]) -> dict[str, dict[str, str]]:
    images: dict[str, dict[str, str]] = {}
    for value in values:
        if "=" not in value:
            raise BundleError(f"image must use COMPONENT=REFERENCE form: {value}")
        component, reference = value.split("=", 1)
        if component not in COMPONENTS:
            raise BundleError(f"unknown image component: {component}")
        if component in images:
            raise BundleError(f"duplicate image component: {component}")
        match = REFERENCE_PATTERN.fullmatch(reference)
        if match is None:
            raise BundleError(f"image must be locked to a sha256 digest: {component}")
        repository = match.group("repository")
        expected_suffix = f"agent-browser-cloud-{component}"
        if not repository.endswith(f"/{expected_suffix}"):
            raise BundleError(
                f"{component} image repository must end with /{expected_suffix}"
            )
        images[component] = {
            "reference": reference,
            "repository": repository,
            "digest": match.group("digest"),
        }
    missing = sorted(set(COMPONENTS) - set(images))
    if missing:
        raise BundleError(f"missing image components: {','.join(missing)}")
    return images


def parse_evidence(values: list[str]) -> dict[str, Path]:
    evidence: dict[str, Path] = {}
    for value in values:
        if "=" not in value:
            raise BundleError(f"evidence must use COMPONENT=PATH form: {value}")
        component, raw_path = value.split("=", 1)
        if component not in COMPONENTS:
            raise BundleError(f"unknown evidence component: {component}")
        if component in evidence:
            raise BundleError(f"duplicate evidence component: {component}")
        path = Path(raw_path).resolve()
        if not path.is_file():
            raise BundleError(f"SBOM evidence is missing: {component}")
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise BundleError(f"SBOM evidence is invalid: {component}: {error}") from error
        if not isinstance(document, dict) or not str(
            document.get("spdxVersion", "")
        ).startswith("SPDX-"):
            raise BundleError(f"SBOM evidence is not an SPDX JSON document: {component}")
        evidence[component] = path
    missing = sorted(set(COMPONENTS) - set(evidence))
    if missing:
        raise BundleError(f"missing SBOM evidence: {','.join(missing)}")
    return evidence


def kustomization(images: dict[str, dict[str, str]]) -> str:
    lines = [
        "apiVersion: kustomize.config.k8s.io/v1beta1",
        "kind: Kustomization",
        "resources:",
        "  - ../base",
        "images:",
    ]
    for component, base_name in COMPONENTS.items():
        image = images[component]
        lines.extend(
            [
                f"  - name: {base_name}",
                f"    newName: {image['repository']}",
                f"    digest: {image['digest']}",
            ]
        )
    return "\n".join(lines) + "\n"


def render(args: argparse.Namespace) -> None:
    if COMMIT_PATTERN.fullmatch(args.source_commit) is None:
        raise BundleError("source commit must be a full lowercase Git SHA")
    images = parse_images(args.image)
    evidence_sources = parse_evidence(args.evidence)
    base_dir = args.base_dir.resolve()
    if not (base_dir / "kustomization.yaml").is_file():
        raise BundleError(f"Kubernetes base is missing: {base_dir}")
    output = args.output.resolve()
    if output.exists():
        raise BundleError(f"output directory already exists: {output}")

    output.mkdir(parents=True)
    shutil.copytree(base_dir, output / "base")
    production = output / "production"
    production.mkdir()
    kustomization_path = production / "kustomization.yaml"
    kustomization_path.write_text(kustomization(images), encoding="utf-8")
    evidence_dir = output / "evidence"
    evidence_dir.mkdir()
    evidence = {}
    for component in COMPONENTS:
        destination = evidence_dir / f"{component}.spdx.json"
        shutil.copyfile(evidence_sources[component], destination)
        evidence[component] = {
            "path": f"evidence/{component}.spdx.json",
            "digest": sha256_file(destination),
            "mediaType": SPDX_MEDIA_TYPE,
        }
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "sourceCommit": args.source_commit,
        "images": images,
        "evidence": evidence,
        "deployment": {
            "entrypoint": "production/kustomization.yaml",
            "kustomizationDigest": sha256_file(kustomization_path),
        },
    }
    (output / "release-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    verify_bundle(output)


def verify_bundle(bundle: Path) -> None:
    bundle = bundle.resolve()
    manifest_path = bundle / "release-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BundleError(f"invalid release manifest: {error}") from error
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise BundleError("unsupported release manifest schema")
    source_commit = manifest.get("sourceCommit", "")
    if not isinstance(source_commit, str) or COMMIT_PATTERN.fullmatch(source_commit) is None:
        raise BundleError("release manifest source commit is invalid")
    raw_images = manifest.get("images")
    if not isinstance(raw_images, dict):
        raise BundleError("release manifest images are missing")
    values = []
    for component in COMPONENTS:
        entry = raw_images.get(component)
        if not isinstance(entry, dict) or not isinstance(entry.get("reference"), str):
            raise BundleError(f"release manifest image is missing: {component}")
        values.append(f"{component}={entry['reference']}")
    images = parse_images(values)
    if raw_images != images:
        raise BundleError("release manifest image metadata is not canonical")

    raw_evidence = manifest.get("evidence")
    if not isinstance(raw_evidence, dict):
        raise BundleError("release manifest evidence is missing")
    evidence = {}
    for component in COMPONENTS:
        relative_path = f"evidence/{component}.spdx.json"
        entry = raw_evidence.get(component)
        if (
            not isinstance(entry, dict)
            or entry.get("path") != relative_path
            or entry.get("mediaType") != SPDX_MEDIA_TYPE
        ):
            raise BundleError(f"release manifest evidence is invalid: {component}")
        evidence_path = bundle / relative_path
        try:
            document = json.loads(evidence_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise BundleError(f"SBOM evidence is invalid: {component}: {error}") from error
        if not isinstance(document, dict) or not str(
            document.get("spdxVersion", "")
        ).startswith("SPDX-"):
            raise BundleError(f"SBOM evidence is not an SPDX JSON document: {component}")
        evidence[component] = {
            "path": relative_path,
            "digest": sha256_file(evidence_path),
            "mediaType": SPDX_MEDIA_TYPE,
        }
    if raw_evidence != evidence:
        raise BundleError("release manifest evidence metadata is not canonical")

    deployment = manifest.get("deployment")
    if not isinstance(deployment, dict):
        raise BundleError("release manifest deployment metadata is missing")
    if deployment.get("entrypoint") != "production/kustomization.yaml":
        raise BundleError("release manifest deployment entrypoint is invalid")
    kustomization_path = bundle / "production" / "kustomization.yaml"
    try:
        actual_kustomization = kustomization_path.read_text(encoding="utf-8")
    except OSError as error:
        raise BundleError(f"production kustomization is missing: {error}") from error
    if actual_kustomization != kustomization(images):
        raise BundleError("production kustomization differs from signed image metadata")
    if deployment.get("kustomizationDigest") != sha256_file(kustomization_path):
        raise BundleError("production kustomization digest mismatch")
    if not (bundle / "base" / "kustomization.yaml").is_file():
        raise BundleError("release bundle Kubernetes base is missing")


def verify(args: argparse.Namespace) -> None:
    verify_bundle(args.bundle)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    render_parser = commands.add_parser("render")
    render_parser.add_argument("--source-commit", required=True)
    render_parser.add_argument("--output", type=Path, required=True)
    render_parser.add_argument(
        "--base-dir", type=Path, default=Path("deploy/kubernetes/base")
    )
    render_parser.add_argument("--image", action="append", default=[], required=True)
    render_parser.add_argument("--evidence", action="append", default=[], required=True)
    render_parser.set_defaults(handler=render)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--bundle", type=Path, required=True)
    verify_parser.set_defaults(handler=verify)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.handler(args)
    except BundleError as error:
        print(f"release bundle rejected: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
