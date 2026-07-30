import { identityHeaders, SessionApiError } from './session';
import type {
  UpdateUserPreferencesRequest,
  UserPreferencesView,
} from '@/types/userPreferences';

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

export async function getUserPreferences(
  signal?: AbortSignal
): Promise<UserPreferencesView> {
  const response = await fetch(`${API_BASE}/user-preferences`, {
    signal,
    headers: {
      Accept: 'application/json',
      ...identityHeaders(),
    },
  });
  return parseOrThrow<UserPreferencesView>(response);
}

export async function updateUserPreferences(
  request: UpdateUserPreferencesRequest
): Promise<UserPreferencesView> {
  const response = await fetch(`${API_BASE}/user-preferences`, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...identityHeaders(),
    },
    body: JSON.stringify(request),
  });
  return parseOrThrow<UserPreferencesView>(response);
}
