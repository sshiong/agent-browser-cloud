import { consumeEventStream, requireMatchingEventId } from './eventStream';
import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  AuditEventListResponse,
  AuditEventStreamControl,
  AuditEventStreamEvent,
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

function roleHeaders(options: {
  securityAdmin?: boolean;
  platformAdmin?: boolean;
}): Record<string, string> {
  const identity = identityHeaders(DEFAULT_TENANT_ID);
  return {
    ...identity,
    ...(!('Authorization' in identity) && options.securityAdmin
      ? { 'X-Roles': 'SECURITY_ADMIN' }
      : !('Authorization' in identity) && options.platformAdmin
        ? { 'X-Roles': 'PLATFORM_ADMIN' }
        : {}),
  };
}

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
  const response = await fetch(`${API_BASE}${path}`, {
    signal: options.signal,
    method: options.method ?? 'GET',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    headers: {
      Accept: 'application/json',
      ...(options.body === undefined
        ? {}
        : { 'Content-Type': 'application/json' }),
      ...roleHeaders(options),
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

export async function streamAuditEventChanges(callbacks: {
  lastEventId?: string;
  signal: AbortSignal;
  onOpen: () => void;
  onControl: (control: AuditEventStreamControl) => void;
  onChange: (change: AuditEventStreamEvent) => void;
}): Promise<void> {
  const response = await fetch(`${API_BASE}/audit-events/event-stream`, {
    signal: callbacks.signal,
    headers: {
      Accept: 'text/event-stream',
      ...roleHeaders({ securityAdmin: true }),
      ...(callbacks.lastEventId
        ? { 'Last-Event-ID': callbacks.lastEventId }
        : {}),
    },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'AUDIT_STREAM_UNAVAILABLE',
      message: `Audit event stream failed with status ${response.status}`,
      details: {},
      requestId: '',
      timestamp: new Date().toISOString(),
    }));
    throw new SessionApiError(response.status, body);
  }
  if (!response.body) {
    throw new Error('Audit event stream response body is unavailable');
  }
  callbacks.onOpen();
  let acceptedCursor: number | undefined;
  await consumeEventStream(response.body, ({ id, event, data }) => {
    const parsed: unknown = JSON.parse(data);
    if (event === 'audit-stream-ready' || event === 'audit-stream-reset') {
      if (!isAuditStreamControl(parsed)) {
        throw new Error('Audit event stream control is invalid');
      }
      requireMatchingEventId(id, parsed.cursor, 'Audit event stream');
      acceptedCursor = parsed.cursor;
      callbacks.onControl(parsed);
      return;
    }
    if (event === 'audit-change') {
      if (!isAuditStreamEvent(parsed)) {
        throw new Error('Audit event stream event is invalid');
      }
      requireMatchingEventId(id, parsed.sequence, 'Audit event stream');
      if (acceptedCursor !== undefined && parsed.sequence <= acceptedCursor) {
        throw new Error('Audit event stream sequence did not advance');
      }
      acceptedCursor = parsed.sequence;
      callbacks.onChange(parsed);
    }
  });
}

function isAuditStreamControl(
  value: unknown
): value is AuditEventStreamControl {
  if (!value || typeof value !== 'object') return false;
  const control = value as Partial<AuditEventStreamControl>;
  return (
    typeof control.cursor === 'number' &&
    Number.isSafeInteger(control.cursor) &&
    control.cursor >= 0 &&
    typeof control.resetRequired === 'boolean' &&
    typeof control.connectedAt === 'string'
  );
}

function isAuditStreamEvent(value: unknown): value is AuditEventStreamEvent {
  if (!value || typeof value !== 'object') return false;
  const change = value as Partial<AuditEventStreamEvent>;
  return (
    typeof change.sequence === 'number' &&
    Number.isSafeInteger(change.sequence) &&
    change.sequence > 0 &&
    typeof change.occurredAt === 'string' &&
    typeof change.replayed === 'boolean'
  );
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
