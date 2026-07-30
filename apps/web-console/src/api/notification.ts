import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  WorkspaceNotificationListResponse,
  WorkspaceNotificationReadState,
} from '@/types/notification';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function parseOrThrow<T>(response: Response): Promise<T> {
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

export async function listWorkspaceNotifications(
  limit = 30,
  beforeSequence?: number,
  signal?: AbortSignal
): Promise<WorkspaceNotificationListResponse> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (beforeSequence !== undefined) {
    params.set('beforeSequence', String(beforeSequence));
  }
  const response = await fetch(`${API_BASE}/notifications?${params}`, {
    signal,
    headers: {
      Accept: 'application/json',
      ...identityHeaders(DEFAULT_TENANT_ID),
    },
  });
  return parseOrThrow<WorkspaceNotificationListResponse>(response);
}

export async function updateWorkspaceNotificationReadCursor(
  readThroughSequence: number
): Promise<WorkspaceNotificationReadState> {
  const response = await fetch(`${API_BASE}/notifications/read-cursor`, {
    method: 'PATCH',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...identityHeaders(DEFAULT_TENANT_ID),
    },
    body: JSON.stringify({ readThroughSequence }),
  });
  return parseOrThrow<WorkspaceNotificationReadState>(response);
}
