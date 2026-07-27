import type {
  SessionView,
  CreateSessionRequest,
  CreateSessionResponse,
  ApiError,
  OperationResponse,
  SessionListResponse,
  BrowserStateView,
  RemoteDesktopConnection,
  StateResyncRequest,
  StateResyncResponse,
  SessionResourceView,
  ResourceEventListResponse,
  ResourcePolicyRequest,
  ResourcePolicyOperationResponse,
} from '../types/session';
import { getRuntimeIdentity } from '@/auth/runtimeIdentity';

/**
 * API 基础 URL。
 */
const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');
export const DEFAULT_TENANT_ID =
  import.meta.env.VITE_TENANT_ID?.trim() || 'tenant-local';
export const DEFAULT_ACTOR_ID =
  import.meta.env.VITE_ACTOR_ID?.trim() || 'user-local';

export function currentTenantId() {
  return getRuntimeIdentity()?.tenantId || DEFAULT_TENANT_ID;
}

export function currentActorId() {
  return getRuntimeIdentity()?.actorId || DEFAULT_ACTOR_ID;
}

export function identityHeaders(
  tenantId = DEFAULT_TENANT_ID,
  actorId = DEFAULT_ACTOR_ID
): Record<string, string> {
  const identity = getRuntimeIdentity();
  if (identity?.accessToken) {
    return { Authorization: `Bearer ${identity.accessToken}` };
  }
  return {
    'X-Tenant-Id': identity?.tenantId || tenantId,
    'X-Actor-Id': identity?.actorId || actorId,
    ...(identity?.roles.length ? { 'X-Roles': identity.roles.join(',') } : {}),
  };
}

/**
 * API 错误类。
 */
export class SessionApiError extends Error {
  constructor(
    public status: number,
    public body: ApiError
  ) {
    super(body.message);
    this.name = 'SessionApiError';
  }
}

export function isSessionApiError(error: unknown): error is SessionApiError {
  return error instanceof SessionApiError;
}

/**
 * 发送 API 请求。
 */
async function request<T>(
  path: string,
  options?: RequestInit,
  tenantId?: string,
  actorId?: string
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(tenantId ? identityHeaders(tenantId, actorId) : {}),
      ...options?.headers,
    },
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'UNKNOWN_ERROR',
      message: `Request failed with status ${response.status}`,
    }));
    throw new SessionApiError(response.status, body);
  }

  return response.json();
}

async function requestOptional<T>(
  path: string,
  options?: RequestInit,
  tenantId?: string,
  actorId?: string
): Promise<T | null> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(tenantId ? identityHeaders(tenantId, actorId) : {}),
      ...options?.headers,
    },
  });

  if (response.status === 204) return null;
  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'UNKNOWN_ERROR',
      message: `Request failed with status ${response.status}`,
    }));
    throw new SessionApiError(response.status, body);
  }
  return response.json();
}

/**
 * 获取 Session 详情。
 */
export async function getSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SessionView> {
  return request<SessionView>(`/sessions/${sessionId}`, { signal }, tenantId);
}

/**
 * 获取 Session 最近一次由 Browser Node 采集的浏览器状态。
 */
export async function getBrowserState(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<BrowserStateView | null> {
  return requestOptional<BrowserStateView>(
    `/sessions/${sessionId}/state`,
    { signal },
    tenantId
  );
}

export async function getSessionResources(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SessionResourceView> {
  return request<SessionResourceView>(
    `/sessions/${sessionId}/resources`,
    { signal },
    tenantId
  );
}

export async function getSessionResourceEvents(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ResourceEventListResponse> {
  return request<ResourceEventListResponse>(
    `/sessions/${sessionId}/resource-events?limit=50`,
    { signal },
    tenantId
  );
}

export async function updateSessionResourcePolicy(
  sessionId: string,
  policy: ResourcePolicyRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ResourcePolicyOperationResponse> {
  return request<ResourcePolicyOperationResponse>(
    `/sessions/${sessionId}/resource-policy`,
    {
      method: 'PATCH',
      body: JSON.stringify(policy),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function resyncBrowserState(
  sessionId: string,
  data: StateResyncRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<StateResyncResponse> {
  return request<StateResyncResponse>(
    `/sessions/${sessionId}:resync-state`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

/**
 * 创建新 Session。
 */
export async function createSession(
  data: CreateSessionRequest,
  idempotencyKey: string,
  signal?: AbortSignal
): Promise<CreateSessionResponse> {
  return request<CreateSessionResponse>(
    '/sessions',
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    },
    data.tenantId
  );
}

/**
 * 启动 Session。
 */
export async function startSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:start`,
    {
      method: 'POST',
      signal,
    },
    tenantId
  );
}

/**
 * 终止 Session。
 */
export async function terminateSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:terminate`,
    {
      method: 'POST',
      signal,
    },
    tenantId
  );
}

export async function requestHumanTakeover(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:takeover`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

export async function releaseHumanTakeover(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:release-takeover`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

export async function createRemoteDesktopConnection(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
): Promise<RemoteDesktopConnection> {
  return request<RemoteDesktopConnection>(
    `/sessions/${sessionId}:desktop-connection`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

/**
 * 列出 Sessions。
 */
export async function listSessions(params?: {
  state?: string;
  query?: string;
  limit?: number;
  offset?: number;
  tenantId?: string;
  signal?: AbortSignal;
}): Promise<SessionListResponse> {
  const searchParams = new URLSearchParams();
  if (params?.state) searchParams.set('state', params.state);
  if (params?.query?.trim()) searchParams.set('q', params.query.trim());
  if (params?.limit) searchParams.set('limit', String(params.limit));
  if (params?.offset) searchParams.set('offset', String(params.offset));

  const query = searchParams.toString();
  return request(
    `/sessions${query ? `?${query}` : ''}`,
    { signal: params?.signal },
    params?.tenantId || DEFAULT_TENANT_ID
  );
}
