import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  WorkspaceTagListResponse,
  WorkspaceTagRequest,
  WorkspaceTagView,
} from '@/types/tag';

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

export function listWorkspaceTags(
  signal?: AbortSignal
): Promise<WorkspaceTagListResponse> {
  return request('/tags', { signal });
}

export function createWorkspaceTag(
  body: WorkspaceTagRequest,
  idempotencyKey: string
): Promise<WorkspaceTagView> {
  return request('/tags', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function updateWorkspaceTag(
  tagId: string,
  body: WorkspaceTagRequest,
  idempotencyKey: string
): Promise<WorkspaceTagView> {
  return request(`/tags/${encodeURIComponent(tagId)}`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function deleteWorkspaceTag(
  tagId: string,
  idempotencyKey: string
): Promise<void> {
  return request(`/tags/${encodeURIComponent(tagId)}`, {
    method: 'DELETE',
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function assignSessionToTag(
  tagId: string,
  sessionId: string,
  idempotencyKey: string
): Promise<WorkspaceTagView> {
  return request(
    `/tags/${encodeURIComponent(tagId)}/sessions/${encodeURIComponent(sessionId)}`,
    {
      method: 'PUT',
      headers: { 'Idempotency-Key': idempotencyKey },
    }
  );
}

export function unassignSessionFromTag(
  tagId: string,
  sessionId: string,
  idempotencyKey: string
): Promise<WorkspaceTagView> {
  return request(
    `/tags/${encodeURIComponent(tagId)}/sessions/${encodeURIComponent(sessionId)}`,
    {
      method: 'DELETE',
      headers: { 'Idempotency-Key': idempotencyKey },
    }
  );
}
