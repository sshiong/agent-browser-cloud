import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type { GlobalSearchResponse, SearchResourceType } from '@/types/search';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

export async function globalSearch(
  query: string,
  types: SearchResourceType[],
  limit = 24,
  signal?: AbortSignal
): Promise<GlobalSearchResponse> {
  const params = new URLSearchParams({
    q: query.trim(),
    limit: String(limit),
  });
  if (types.length > 0) params.set('types', types.join(','));
  const response = await fetch(`${API_BASE}/search?${params}`, {
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
  return response.json() as Promise<GlobalSearchResponse>;
}
