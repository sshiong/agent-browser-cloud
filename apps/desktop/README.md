# Agent Browser Cloud Desktop

Tauri 2 reuses the production React UI and API client from `apps/web-console`.
Desktop-only operations are isolated behind the platform adapter. OIDC authorization
uses the system browser and `agentbrowsercloud://auth/callback`; PKCE and user state
are stored in macOS Keychain or Windows Credential Manager.

## Local development

```bash
pnpm --dir apps/web-console install --frozen-lockfile
pnpm --dir apps/desktop install --frozen-lockfile
pnpm --dir apps/desktop dev
```

The development CSP only allows the local Control Plane and remote-desktop ports.

## Release build

`pnpm --dir apps/desktop build:signed` fails closed unless the following are set:

- `TAURI_SIGNING_PRIVATE_KEY` and its optional password
- `TAURI_UPDATER_PUBKEY`
- `TAURI_UPDATER_ENDPOINT` (HTTPS)
- `VITE_API_BASE_URL` (HTTPS)
- `VITE_OIDC_AUTHORITY` (HTTPS)
- `VITE_OIDC_CLIENT_ID`

On macOS it also requires `APPLE_SIGNING_IDENTITY`, `APPLE_ID`,
`APPLE_PASSWORD`, and `APPLE_TEAM_ID`. On Windows it requires an installed
code-signing certificate identified by `WINDOWS_CERTIFICATE_THUMBPRINT` plus an
HTTPS `WINDOWS_TIMESTAMP_URL`.

The updater key and platform code-signing identity are independent trust chains;
the script refuses to build when either one is absent.
