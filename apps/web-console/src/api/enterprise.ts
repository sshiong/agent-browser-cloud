import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type { EnterpriseOverviewResponse } from '@/types/enterprise';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

export async function getEnterpriseOverview(
  signal?: AbortSignal
): Promise<EnterpriseOverviewResponse> {
  const response = await fetch(`${API_BASE}/enterprise/overview`, {
    signal,
    headers: {
      Accept: 'application/json',
      ...identityHeaders(DEFAULT_TENANT_ID),
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
  return response.json() as Promise<EnterpriseOverviewResponse>;
}
