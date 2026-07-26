/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_DEV_PROXY_TARGET?: string;
  readonly VITE_TENANT_ID?: string;
  readonly VITE_ACTOR_ID?: string;
  readonly VITE_ENABLE_FIXTURES?: string;
  readonly VITE_AUTH_MODE?: 'local' | 'oidc';
  readonly VITE_LOCAL_ROLES?: string;
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_OIDC_SCOPE?: string;
  readonly VITE_OIDC_ROLES_CLAIM?: string;
  readonly VITE_OIDC_TENANT_CLAIM?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
