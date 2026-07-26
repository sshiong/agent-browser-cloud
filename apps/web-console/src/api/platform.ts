import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  AuditEventListResponse,
  BreakGlassRequestListResponse,
  BreakGlassRequestView,
  CompleteKeyRotationRequest,
  CreateBreakGlassRequest,
  CreateKeyRotationRequest,
  KeyRotationRequestListResponse,
  KeyRotationRequestView,
  RuntimeBuildListResponse,
  SecureDebugSessionListResponse,
  SecureDebugSessionView,
  SecureDebugSnapshotView,
} from '@/types/platform';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(
  path: string,
  options: {
    signal?: AbortSignal;
    securityAdmin?: boolean;
    platformAdmin?: boolean;
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
        : !('Authorization' in identity) && options.platformAdmin
          ? { 'X-Roles': 'PLATFORM_ADMIN' }
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

export function listSecureDebugSessions(
  signal?: AbortSignal
): Promise<SecureDebugSessionListResponse> {
  return request('/secure-debug-sessions', { signal, securityAdmin: true });
}

export function startSecureDebugSession(
  requestId: string
): Promise<SecureDebugSessionView> {
  return request(`/break-glass-requests/${requestId}:start-secure-debug`, {
    method: 'POST',
    securityAdmin: true,
  });
}

export function readSecureDebugSnapshot(
  debugSessionId: string
): Promise<SecureDebugSnapshotView> {
  return request(`/secure-debug-sessions/${debugSessionId}/snapshot`, {
    securityAdmin: true,
  });
}

export function endSecureDebugSession(
  debugSessionId: string
): Promise<SecureDebugSessionView> {
  return request(`/secure-debug-sessions/${debugSessionId}:end`, {
    method: 'POST',
    securityAdmin: true,
  });
}

export function listKeyRotationRequests(
  signal?: AbortSignal
): Promise<KeyRotationRequestListResponse> {
  return request('/key-rotation-requests', { signal, platformAdmin: true });
}

export function createKeyRotationRequest(
  input: CreateKeyRotationRequest
): Promise<KeyRotationRequestView> {
  return request('/key-rotation-requests', {
    method: 'POST',
    body: input,
    platformAdmin: true,
  });
}

export function transitionKeyRotationRequest(
  rotationId: string,
  transition: 'approve' | 'revoke'
): Promise<KeyRotationRequestView> {
  return request(`/key-rotation-requests/${rotationId}:${transition}`, {
    method: 'POST',
    platformAdmin: true,
  });
}

export function completeKeyRotationRequest(
  rotationId: string,
  completion: CompleteKeyRotationRequest
): Promise<KeyRotationRequestView> {
  return request(`/key-rotation-requests/${rotationId}:complete`, {
    method: 'POST',
    body: completion,
    platformAdmin: true,
  });
}
