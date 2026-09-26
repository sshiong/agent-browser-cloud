#!/usr/bin/env python3
"""Build pinned MinIO test images when their former public registry is unavailable."""

import argparse
import json
import os
import pathlib
import platform
import shutil
import subprocess
import tempfile


MINIO_COMMIT = "0d7408fc9969caf07de6a8c3a84f9fbb10a6739e"
MC_COMMIT = "b00526b153a31b36767991a4f5ce2cced435ee8e"
MINIO_TAG = "RELEASE.2025-04-22T22-12-26Z"
MC_TAG = "RELEASE.2025-04-16T18-13-26Z"
MINIO_IMAGE = os.environ.get("MINIO_IMAGE", f"quay.io/minio/minio:{MINIO_TAG}")
MC_IMAGE = os.environ.get("MINIO_MC_IMAGE", f"quay.io/minio/mc:{MC_TAG}")


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def image_exists(image):
    return subprocess.run(
        ["docker", "image", "inspect", image],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    ).returncode == 0


def source_dir(module, commit):
    result = run("go", "mod", "download", "-json", f"{module}@{commit}", capture_output=True)
    details = json.loads(result.stdout)
    if details.get("Error") or not details.get("Dir"):
        raise RuntimeError(f"pinned source download failed for {module}@{commit}")
    return pathlib.Path(details["Dir"])


def build_binary(module, commit, tag, arch, output):
    source = source_dir(module, commit)
    package = f"{module}/cmd"
    release_time = tag.removeprefix("RELEASE.")
    date, clock = release_time.split("T", 1)
    version = f"{date}T{clock[:2]}:{clock[3:5]}:{clock[6:8]}Z"
    flags = " ".join(
        [
            "-s -w",
            f"-X {package}.Version={version}",
            f"-X {package}.ReleaseTag={tag}",
            f"-X {package}.CommitID={commit}",
            f"-X {package}.ShortCommitID={commit[:12]}",
            f"-X {package}.CopyrightYear={date[:4]}",
        ]
    )
    env = os.environ.copy()
    env.update(GOOS="linux", GOARCH=arch, CGO_ENABLED="0")
    run(
        "go", "build", "-mod=readonly", "-trimpath", "-ldflags", flags,
        "-o", str(output), ".", cwd=source, env=env,
    )


def build_image(binary, image, arch, directory, base):
    context = directory / f"{binary.name}-context"
    context.mkdir()
    destination = context / binary.name
    shutil.copyfile(binary, destination)
    destination.chmod(0o755)
    if base:
        dockerfile = f"FROM {base}\nCOPY {binary.name} /usr/local/bin/{binary.name}\n"
    else:
        dockerfile = f"FROM scratch\nCOPY {binary.name} /usr/local/bin/{binary.name}\n"
    dockerfile += f'ENTRYPOINT ["/usr/local/bin/{binary.name}"]\n'
    (context / "Dockerfile").write_text(dockerfile)
    run("docker", "build", "--platform", f"linux/{arch}", "-t", image, str(context))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="rebuild even if both images exist")
    args = parser.parse_args()
    if platform.system() == "Darwin":
        status = run("orbctl", "status", capture_output=True).stdout.strip()
        context = run("docker", "context", "show", capture_output=True).stdout.strip()
        info = run(
            "docker", "info", "--format", "Name={{.Name}} OS={{.OperatingSystem}} Server={{.ServerVersion}}",
            capture_output=True,
        ).stdout.strip()
        print(status, context, info, sep="\n")
        if "Running" not in status or context != "orbstack" or "OS=OrbStack" not in info:
            raise RuntimeError("OrbStack Docker preflight failed")
    if not args.force and image_exists(MINIO_IMAGE) and image_exists(MC_IMAGE):
        print("Pinned MinIO test images already exist locally.")
        return
    architecture = run("docker", "info", "--format", "{{.Architecture}}", capture_output=True).stdout.strip()
    arch = {"aarch64": "arm64", "arm64": "arm64", "x86_64": "amd64", "amd64": "amd64"}.get(architecture)
    if not arch:
        raise RuntimeError(f"unsupported Docker architecture: {architecture}")
    with tempfile.TemporaryDirectory(prefix="agentbrowser-minio-source-") as temporary:
        directory = pathlib.Path(temporary)
        minio = directory / "minio"
        mc = directory / "mc"
        build_binary("github.com/minio/minio", MINIO_COMMIT, MINIO_TAG, arch, minio)
        build_binary("github.com/minio/mc", MC_COMMIT, MC_TAG, arch, mc)
        build_image(minio, MINIO_IMAGE, arch, directory, None)
        base = os.environ.get("MINIO_SOURCE_MC_BASE_IMAGE", "alpine:3.22")
        build_image(mc, MC_IMAGE, arch, directory, base)
    for image, tag, commit in ((MINIO_IMAGE, MINIO_TAG, MINIO_COMMIT), (MC_IMAGE, MC_TAG, MC_COMMIT)):
        output = run("docker", "run", "--rm", image, "--version", capture_output=True).stdout
        if tag not in output or commit not in output:
            raise RuntimeError(f"source-built image version mismatch: {image}: {output}")
        print(output.strip())


if __name__ == "__main__":
    main()
