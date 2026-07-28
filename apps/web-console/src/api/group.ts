import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  WorkspaceGroupListResponse,
  WorkspaceGroupRequest,
  WorkspaceGroupView,
} from '@/types/group';

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

export function listWorkspaceGroups(
  signal?: AbortSignal
): Promise<WorkspaceGroupListResponse> {
  return request('/groups', { signal });
}

export function createWorkspaceGroup(
  body: WorkspaceGroupRequest,
  idempotencyKey: string
): Promise<WorkspaceGroupView> {
  return request('/groups', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function updateWorkspaceGroup(
  groupId: string,
  body: WorkspaceGroupRequest,
  idempotencyKey: string
): Promise<WorkspaceGroupView> {
  return request(`/groups/${encodeURIComponent(groupId)}`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function deleteWorkspaceGroup(
  groupId: string,
  idempotencyKey: string
): Promise<void> {
  return request(`/groups/${encodeURIComponent(groupId)}`, {
    method: 'DELETE',
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function assignSessionToGroup(
  groupId: string,
  sessionId: string,
  idempotencyKey: string
): Promise<WorkspaceGroupView> {
  return request(
    `/groups/${encodeURIComponent(groupId)}/sessions/${encodeURIComponent(sessionId)}`,
    {
      method: 'PUT',
      headers: { 'Idempotency-Key': idempotencyKey },
    }
  );
}

export function unassignSessionFromGroup(
  groupId: string,
  sessionId: string,
  idempotencyKey: string
): Promise<WorkspaceGroupView> {
  return request(
    `/groups/${encodeURIComponent(groupId)}/sessions/${encodeURIComponent(sessionId)}`,
    {
      method: 'DELETE',
      headers: { 'Idempotency-Key': idempotencyKey },
    }
  );
}
