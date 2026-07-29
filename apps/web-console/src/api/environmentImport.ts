import {
  currentActorId,
  currentTenantId,
  identityHeaders,
  SessionApiError,
} from './session';
import type {
  EnvironmentImport,
  EnvironmentImportListResponse,
  PreviewEnvironmentImportRequest,
} from '@/types/environmentImport';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...identityHeaders(currentTenantId(), currentActorId()),
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

export function listEnvironmentImports(
  signal?: AbortSignal
): Promise<EnvironmentImportListResponse> {
  return request('/environment-imports', { signal });
}

export function previewEnvironmentImport(
  body: PreviewEnvironmentImportRequest,
  idempotencyKey: string
): Promise<EnvironmentImport> {
  return request('/environment-imports:preview', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function commitEnvironmentImport(
  environmentImport: EnvironmentImport,
  idempotencyKey: string
): Promise<EnvironmentImport> {
  return request(
    `/environment-imports/${encodeURIComponent(environmentImport.importId)}:commit`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ expectedVersion: environmentImport.version }),
    }
  );
}
