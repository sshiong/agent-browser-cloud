import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  WorkspaceSettingsRequest,
  WorkspaceSettingsView,
} from '@/types/settings';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...identityHeaders(DEFAULT_TENANT_ID),
      ...options?.headers,
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

export function getWorkspaceSettings(
  signal?: AbortSignal
): Promise<WorkspaceSettingsView> {
  return request('/workspace-settings', { signal });
}

export function updateWorkspaceSettings(
  body: WorkspaceSettingsRequest,
  idempotencyKey: string
): Promise<WorkspaceSettingsView> {
  return request('/workspace-settings', {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}
