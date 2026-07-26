import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  BrowserNodeListResponse,
  ExtensionProfileListResponse,
} from '@/types/capacity';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    signal,
    headers: {
      Accept: 'application/json',
      ...identityHeaders(DEFAULT_TENANT_ID),
    },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'UNKNOWN_ERROR',
      message: `Request failed with status ${response.status}`,
      details: {},
      requestId: '',
      timestamp: new Date().toISOString(),
    }));
    throw new SessionApiError(response.status, body);
  }
  return response.json() as Promise<T>;
}

export function listBrowserNodes(
  signal?: AbortSignal
): Promise<BrowserNodeListResponse> {
  return request('/browser-nodes', signal);
}

export function listExtensionProfiles(
  signal?: AbortSignal
): Promise<ExtensionProfileListResponse> {
  return request('/extensions', signal);
}
