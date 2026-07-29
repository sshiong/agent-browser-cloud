import {
  DEFAULT_TENANT_ID,
  identityHeaders,
  SessionApiError,
} from '@/api/session';
import type {
  CreateProfileRequest,
  ProfileImportListResponse,
  ProfileImportRequest,
  ProfileImportView,
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

async function multipartRequest<T>(
  path: string,
  form: FormData,
  idempotencyKey: string,
  tenantId: string
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      ...identityHeaders(tenantId),
      'Idempotency-Key': idempotencyKey,
    },
    body: form,
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

export function listProfileImports(
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ProfileImportListResponse> {
  return request('/profile-imports?limit=20', tenantId, { signal });
}

export function importProfileCheckpoint(
  data: ProfileImportRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID
): Promise<ProfileImportView> {
  const form = new FormData();
  form.set('profileId', data.profileId);
  form.set('profileName', data.profileName);
  if (data.profileDescription) {
    form.set('profileDescription', data.profileDescription);
  }
  form.set('runtimeBuildId', data.runtimeBuildId);
  form.set('archiveSha256', data.archiveSha256);
  form.set('archive', data.archive, data.archive.name);
  return multipartRequest('/profile-imports', form, idempotencyKey, tenantId);
}
