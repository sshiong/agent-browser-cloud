import {
  DEFAULT_TENANT_ID,
  identityHeaders,
  SessionApiError,
} from '@/api/session';
import type { ProxyOverviewResponse } from '@/types/proxy';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

export async function getProxyOverview(
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ProxyOverviewResponse> {
  const response = await fetch(`${API_BASE}/proxies`, {
    signal,
    headers: {
      'Content-Type': 'application/json',
      ...identityHeaders(tenantId),
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
