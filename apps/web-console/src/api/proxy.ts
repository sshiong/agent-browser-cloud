import {
  DEFAULT_TENANT_ID,
  identityHeaders,
  SessionApiError,
} from '@/api/session';
import type {
  ProxyBindingListResponse,
  ProxyBindingRequest,
  ProxyBindingView,
  ProxyOverviewResponse,
} from '@/types/proxy';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

export async function getProxyOverview(
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ProxyOverviewResponse> {
  const response = await fetch(`${API_BASE}/proxies`, {
    signal,
    headers: {
      'Content-Type': 'application/json',
      ...identityHeaders(tenantId),
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
  return response.json();
}

async function bindingRequest<T>(
  path: string,
  options?: RequestInit
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
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
  if (response.status === 204) return undefined as T;
  return response.json();
}

export function listProxyBindings(signal?: AbortSignal) {
  return bindingRequest<ProxyBindingListResponse>('/proxy-bindings', {
    signal,
  });
}

export function createProxyBinding(
  body: ProxyBindingRequest,
  idempotencyKey: string
) {
  return bindingRequest<ProxyBindingView>('/proxy-bindings', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function updateProxyBinding(
  bindingProfileId: string,
  body: ProxyBindingRequest,
  idempotencyKey: string
) {
  return bindingRequest<ProxyBindingView>(
    `/proxy-bindings/${bindingProfileId}`,
    {
      method: 'PUT',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(body),
    }
  );
}

export function deleteProxyBinding(
  bindingProfileId: string,
  idempotencyKey: string
) {
  return bindingRequest<void>(`/proxy-bindings/${bindingProfileId}`, {
    method: 'DELETE',
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
