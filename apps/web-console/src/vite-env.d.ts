/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_DEV_PROXY_TARGET?: string;
  readonly VITE_TENANT_ID?: string;
  readonly VITE_ENABLE_FIXTURES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
