import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  CreateWorkspaceBatchOperationRequest,
  WorkspaceBatchOperation,
  WorkspaceBatchOperationListResponse,
} from '@/types/workspaceBatch';

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

export function createWorkspaceBatchOperation(
  body: CreateWorkspaceBatchOperationRequest,
  idempotencyKey: string
): Promise<WorkspaceBatchOperation> {
  return request('/workspace-batch-operations', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function getWorkspaceBatchOperation(
  batchOperationId: string,
  signal?: AbortSignal
): Promise<WorkspaceBatchOperation> {
  return request(
    `/workspace-batch-operations/${encodeURIComponent(batchOperationId)}`,
    { signal }
  );
}

export function listWorkspaceBatchOperations(
  limit = 20,
  signal?: AbortSignal
): Promise<WorkspaceBatchOperationListResponse> {
  return request(`/workspace-batch-operations?limit=${limit}`, { signal });
}

export function cancelWorkspaceBatchOperation(
  batchOperationId: string,
  reason: string,
  idempotencyKey: string
): Promise<WorkspaceBatchOperation> {
  return request(
    `/workspace-batch-operations/${encodeURIComponent(batchOperationId)}:cancel`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ reason }),
    }
  );
}
