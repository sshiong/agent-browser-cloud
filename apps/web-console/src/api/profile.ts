import {
  DEFAULT_TENANT_ID,
  identityHeaders,
  SessionApiError,
} from '@/api/session';
import type {
  CreateProfileRequest,
  ProfileListResponse,
  ProfileView,
} from '@/types/profile';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(
  path: string,
  tenantId: string,
  options?: RequestInit
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...identityHeaders(tenantId),
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
  return response.json();
}

export function listProfiles(
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ProfileListResponse> {
  return request('/profiles', tenantId, { signal });
}

export function createProfile(
  data: CreateProfileRequest,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ProfileView> {
  return request('/profiles', tenantId, {
    method: 'POST',
    body: JSON.stringify(data),
    signal,
  });
}
