import type {
  SessionView,
  CreateSessionRequest,
  CreateSessionResponse,
  SessionStateView,
  ApiError,
} from '../types/session';

/**
 * API 基础 URL。
 */
const API_BASE = '/api/v1';
const DEFAULT_TENANT_ID = import.meta.env.VITE_TENANT_ID || 'tenant-local';

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

/**
 * 发送 API 请求。
 */
async function request<T>(
  path: string,
  options?: RequestInit,
  tenantId?: string
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}),
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

/**
 * 获取 Session 详情。
 */
export async function getSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID
): Promise<SessionView> {
  return request<SessionView>(`/sessions/${sessionId}`, undefined, tenantId);
}

/**
 * 创建新 Session。
 */
export async function createSession(
  data: CreateSessionRequest,
  idempotencyKey: string
): Promise<CreateSessionResponse> {
  return request<CreateSessionResponse>('/sessions', {
    method: 'POST',
    body: JSON.stringify(data),
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
  });
}

/**
 * 启动 Session。
 */
export async function startSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID
): Promise<{ operationId: string; state: string }> {
  return request(
    `/sessions/${sessionId}:start`,
    {
      method: 'POST',
    },
    tenantId
  );
}

/**
 * 终止 Session。
 */
export async function terminateSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID
): Promise<{ operationId: string; state: string }> {
  return request(
    `/sessions/${sessionId}:terminate`,
    {
      method: 'POST',
    },
    tenantId
  );
}

/**
 * 获取 Session 状态。
 */
export async function getSessionState(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID
): Promise<SessionStateView> {
  return request<SessionStateView>(
    `/sessions/${sessionId}/state`,
    undefined,
    tenantId
  );
}

/**
 * 列出 Sessions。
 */
export async function listSessions(params?: {
  state?: string;
  limit?: number;
  offset?: number;
  tenantId?: string;
}): Promise<{ items: SessionView[]; total: number }> {
  const searchParams = new URLSearchParams();
  if (params?.state) searchParams.set('state', params.state);
  if (params?.limit) searchParams.set('limit', String(params.limit));
  if (params?.offset) searchParams.set('offset', String(params.offset));

  const query = searchParams.toString();
  return request(
    `/sessions${query ? `?${query}` : ''}`,
    undefined,
    params?.tenantId || DEFAULT_TENANT_ID
  );
}
