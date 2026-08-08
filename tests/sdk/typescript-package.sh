#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sdk_pack_dir="$(mktemp -d "${TMPDIR:-/tmp}/browsercloud-sdk-pack.XXXXXX")"
trap 'rm -rf -- "${sdk_pack_dir}"' EXIT

pnpm --dir "${repository_root}/sdks/typescript" pack --pack-destination "${sdk_pack_dir}" >/dev/null
sdk_archive_count="$(find "${sdk_pack_dir}" -maxdepth 1 -type f -name '*.tgz' | wc -l | tr -d ' ')"
if [[ "${sdk_archive_count}" -ne 1 ]]; then
  echo "expected exactly one TypeScript SDK package" >&2
  exit 1
fi
sdk_archive="$(find "${sdk_pack_dir}" -maxdepth 1 -type f -name '*.tgz' -print)"

archive_listing="$(tar -tzf "${sdk_archive}")"
grep -qx 'package/dist/index.js' <<<"${archive_listing}"
grep -qx 'package/dist/index.d.ts' <<<"${archive_listing}"
grep -qx 'package/dist/generated/index.js' <<<"${archive_listing}"
grep -qx 'package/dist/generated/index.d.ts' <<<"${archive_listing}"
if grep -q '^package/src/' <<<"${archive_listing}"; then
  echo "published TypeScript SDK must not contain source files" >&2
  exit 1
fi

echo "typescript_sdk_pack=true source_files=false"
