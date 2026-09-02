# Local Chromium sandbox

`chromium-seccomp.json` vendors the Apache-2.0 Moby v28.3.3 default profile:
https://github.com/moby/moby/blob/v28.3.3/profiles/seccomp/default.json

The only additional rule allows `clone`, `setns`, and `unshare` for Chromium's
user namespace sandbox, following https://playwright.dev/docs/docker.
The default remains `SCMP_ACT_ERRNO`; existing capability-gated rules are retained.
Only the Compose Browser Node uses this profile. It still runs as `browsercloud`,
without `privileged`, `SYS_ADMIN`, `seccomp=unconfined`, or `--no-sandbox`.
The image installs Debian's `chromium-sandbox` package explicitly.

Revalidate on kernel/engine updates. This local development configuration does not
replace target-platform production sandbox validation.
