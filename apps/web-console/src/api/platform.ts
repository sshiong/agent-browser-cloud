import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  AuditEventListResponse,
  RuntimeBuildListResponse,
} from '@/types/platform';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(
  path: string,
  signal?: AbortSignal,
  securityAdmin = false
): Promise<T> {
  const identity = identityHeaders(DEFAULT_TENANT_ID);
  const response = await fetch(`${API_BASE}${path}`, {
    signal,
    headers: {
      Accept: 'application/json',
      ...identity,
      ...(!('Authorization' in identity) && securityAdmin
        ? { 'X-Roles': 'SECURITY_ADMIN' }
        : {}),
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

export function listAuditEvents(
  eventType?: string,
  signal?: AbortSignal
): Promise<AuditEventListResponse> {
  const query = new URLSearchParams({ limit: '200' });
  if (eventType) query.set('eventType', eventType);
  return request(`/audit-events?${query}`, signal, true);
}

export function listRuntimeBuilds(
  signal?: AbortSignal
): Promise<RuntimeBuildListResponse> {
  return request('/runtime-builds', signal);
}
