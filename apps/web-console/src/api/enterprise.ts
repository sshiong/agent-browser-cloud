import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  EnterpriseOverviewResponse,
  RecoveryGameDayEventPage,
  RecoveryGameDayRemediationView,
  RecoveryGameDayReportExportView,
} from '@/types/enterprise';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

export async function getEnterpriseOverview(
  signal?: AbortSignal
): Promise<EnterpriseOverviewResponse> {
  return requestEnterprise<EnterpriseOverviewResponse>(
    '/enterprise/overview',
    { method: 'GET' },
    signal
  );
}

export async function getRecoveryGameDayEvents(
  gameDayId: string,
  signal?: AbortSignal
): Promise<RecoveryGameDayEventPage> {
  return requestEnterprise<RecoveryGameDayEventPage>(
    `/enterprise/recovery-gamedays/${encodeURIComponent(gameDayId)}/events?limit=20`,
    { method: 'GET' },
    signal
  );
}

export async function generateRecoveryGameDayReport(
  gameDayId: string
): Promise<RecoveryGameDayReportExportView> {
  return requestEnterprise<RecoveryGameDayReportExportView>(
    `/enterprise/recovery-gamedays/${encodeURIComponent(gameDayId)}/exports`,
    { method: 'POST' }
  );
}

export async function updateRecoveryGameDayRemediation(
  ticketId: string,
  input: {
    state: 'ACKNOWLEDGED' | 'RESOLVED';
    ownerId: string;
    resolution?: string;
  }
): Promise<RecoveryGameDayRemediationView> {
  return requestEnterprise<RecoveryGameDayRemediationView>(
    `/enterprise/recovery-gameday-remediations/${encodeURIComponent(ticketId)}`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    }
  );
}

async function requestEnterprise<T>(
  path: string,
  init: RequestInit,
  signal?: AbortSignal
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    signal,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...identityHeaders(DEFAULT_TENANT_ID),
      ...init.headers,
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
  return response.json() as Promise<T>;
}
