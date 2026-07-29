import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  CreateEnvironmentSavedViewRequest,
  EnvironmentSavedView,
  EnvironmentSavedViewListResponse,
  UpdateEnvironmentSavedViewRequest,
} from '@/types/savedView';

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
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function listEnvironmentSavedViews(
  signal?: AbortSignal
): Promise<EnvironmentSavedViewListResponse> {
  return request('/environment-saved-views', { signal });
}

export function createEnvironmentSavedView(
  body: CreateEnvironmentSavedViewRequest,
  idempotencyKey: string
): Promise<EnvironmentSavedView> {
  return request('/environment-saved-views', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function updateEnvironmentSavedView(
  savedViewId: string,
  body: UpdateEnvironmentSavedViewRequest,
  idempotencyKey: string
): Promise<EnvironmentSavedView> {
  return request(
    `/environment-saved-views/${encodeURIComponent(savedViewId)}`,
    {
      method: 'PUT',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(body),
    }
  );
}

export function deleteEnvironmentSavedView(
  savedViewId: string,
  expectedVersion: number,
  idempotencyKey: string
): Promise<void> {
  const params = new URLSearchParams({
    expectedVersion: String(expectedVersion),
  });
  return request(
    `/environment-saved-views/${encodeURIComponent(savedViewId)}?${params}`,
    {
      method: 'DELETE',
      headers: { 'Idempotency-Key': idempotencyKey },
    }
  );
}
