import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  AuditEventListResponse,
  BreakGlassRequestListResponse,
  BreakGlassRequestView,
  CreateBreakGlassRequest,
  RuntimeBuildListResponse,
} from '@/types/platform';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(
  path: string,
  options: {
    signal?: AbortSignal;
    securityAdmin?: boolean;
    method?: 'GET' | 'POST';
    body?: unknown;
  } = {}
): Promise<T> {
  const identity = identityHeaders(DEFAULT_TENANT_ID);
  const response = await fetch(`${API_BASE}${path}`, {
    signal: options.signal,
    method: options.method ?? 'GET',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    headers: {
      Accept: 'application/json',
      ...(options.body === undefined
        ? {}
        : { 'Content-Type': 'application/json' }),
      ...identity,
      ...(!('Authorization' in identity) && options.securityAdmin
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
  return request(`/audit-events?${query}`, { signal, securityAdmin: true });
}

export function listRuntimeBuilds(
  signal?: AbortSignal
): Promise<RuntimeBuildListResponse> {
  return request('/runtime-builds', { signal });
}

export function listBreakGlassRequests(
  signal?: AbortSignal
): Promise<BreakGlassRequestListResponse> {
  return request('/break-glass-requests', { signal, securityAdmin: true });
}

export function createBreakGlassRequest(
  input: CreateBreakGlassRequest
): Promise<BreakGlassRequestView> {
  return request('/break-glass-requests', {
    method: 'POST',
    body: input,
    securityAdmin: true,
  });
}

export function transitionBreakGlassRequest(
  requestId: string,
  transition: 'approve' | 'reject' | 'revoke' | 'review'
): Promise<BreakGlassRequestView> {
  return request(`/break-glass-requests/${requestId}:${transition}`, {
    method: 'POST',
    securityAdmin: true,
  });
}
