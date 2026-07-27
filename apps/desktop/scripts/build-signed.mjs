import { spawnSync } from 'node:child_process';

const commonRequired = [
  'TAURI_SIGNING_PRIVATE_KEY',
  'TAURI_UPDATER_PUBKEY',
  'TAURI_UPDATER_ENDPOINT',
  'VITE_API_BASE_URL',
  'VITE_OIDC_AUTHORITY',
  'VITE_OIDC_CLIENT_ID',
];
const platformRequired =
  process.platform === 'darwin'
    ? [
        'APPLE_SIGNING_IDENTITY',
        'APPLE_ID',
        'APPLE_PASSWORD',
        'APPLE_TEAM_ID',
      ]
    : process.platform === 'win32'
      ? ['WINDOWS_CERTIFICATE_THUMBPRINT', 'WINDOWS_TIMESTAMP_URL']
      : [];
if (platformRequired.length === 0) {
  throw new Error('Signed desktop release is supported only on macOS or Windows');
}

const missing = [...commonRequired, ...platformRequired].filter(
  (name) => !process.env[name]?.trim()
);
if (missing.length > 0) {
  throw new Error(`Signed desktop build is missing: ${missing.join(', ')}`);
}

const updaterEndpoint = requireHttpsUrl(
  'TAURI_UPDATER_ENDPOINT',
  process.env.TAURI_UPDATER_ENDPOINT
);
const apiOrigin = requireHttpsUrl(
  'VITE_API_BASE_URL',
  process.env.VITE_API_BASE_URL
).origin;
const oidcOrigin = requireHttpsUrl(
  'VITE_OIDC_AUTHORITY',
  process.env.VITE_OIDC_AUTHORITY
).origin;
const windowsBundle =
  process.platform === 'win32'
    ? {
        windows: {
          certificateThumbprint: process.env.WINDOWS_CERTIFICATE_THUMBPRINT,
          digestAlgorithm: 'sha256',
          timestampUrl: requireHttpsUrl(
            'WINDOWS_TIMESTAMP_URL',
            process.env.WINDOWS_TIMESTAMP_URL
          ).value,
        },
      }
    : {};
const csp = {
  'default-src': "'self'",
  'base-uri': "'none'",
  'connect-src': `'self' ipc: http://ipc.localhost ${apiOrigin} ${oidcOrigin}`,
  'font-src': "'self' data:",
  'form-action': "'self'",
  'frame-ancestors': "'none'",
  'img-src': "'self' asset: http://asset.localhost blob: data:",
  'object-src': "'none'",
  'script-src': "'self'",
  'style-src': "'self' 'unsafe-inline'",
};
const config = {
  app: { security: { csp } },
  bundle: { createUpdaterArtifacts: true, ...windowsBundle },
  plugins: {
    updater: {
      pubkey: process.env.TAURI_UPDATER_PUBKEY,
      endpoints: [updaterEndpoint.value],
    },
  },
};

const result = spawnSync(
  'pnpm',
  ['exec', 'tauri', 'build', '--config', JSON.stringify(config)],
  { cwd: new URL('..', import.meta.url), env: process.env, stdio: 'inherit' }
);
if (result.error) throw result.error;
process.exit(result.status ?? 1);

function requireHttpsUrl(name, value) {
  const normalized = value.replace(/\{\{[a-z_]+\}\}/gi, 'placeholder');
  const url = new URL(normalized);
  if (url.protocol !== 'https:' || url.username || url.password) {
    throw new Error(`${name} must be an HTTPS URL without credentials`);
  }
  return { value, origin: url.origin };
}
