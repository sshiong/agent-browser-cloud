import { consumeEventStream, requireMatchingEventId } from './eventStream';
import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  EnterpriseOverviewChangeType,
  EnterpriseOverviewResponse,
  EnterpriseOverviewStreamChange,
  EnterpriseOverviewStreamControl,
  RecoveryGameDayEventPage,
  RecoveryGameDayRemediationView,
  RecoveryGameDayReportExportView,
} from '@/types/enterprise';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');
const OVERVIEW_CHANGE_TYPES = new Set<EnterpriseOverviewChangeType>([
  'RUNTIME_VALIDATION',
  'COST_RATE',
  'MEDIA_QUOTA',
  'ERROR_BUDGET',
  'RELEASE_FREEZE',
  'SLA_EXCLUSION',
  'RETENTION',
  'LICENSE',
  'REGION',
  'RECOVERY_GAMEDAY',
  'COMPLIANCE',
]);

export async function getEnterpriseOverview(
  signal?: AbortSignal
): Promise<EnterpriseOverviewResponse> {
  return requestEnterprise<EnterpriseOverviewResponse>(
    '/enterprise/overview',
    { method: 'GET' },
    signal
  );
}

export async function streamEnterpriseOverviewChanges(callbacks: {
  lastEventId?: string;
  signal: AbortSignal;
  onOpen: () => void;
  onControl: (control: EnterpriseOverviewStreamControl) => void;
  onChange: (change: EnterpriseOverviewStreamChange) => void;
}): Promise<void> {
  const response = await fetch(`${API_BASE}/enterprise/overview/event-stream`, {
    signal: callbacks.signal,
    headers: {
      Accept: 'text/event-stream',
      ...identityHeaders(DEFAULT_TENANT_ID),
      ...(callbacks.lastEventId
        ? { 'Last-Event-ID': callbacks.lastEventId }
        : {}),
    },
  });
  if (!response.ok) throw await enterpriseError(response);
  if (!response.body) {
    throw new Error('Enterprise Overview stream response body is unavailable');
  }
  callbacks.onOpen();
  let acceptedCursor: number | undefined;
  await consumeEventStream(response.body, ({ id, event, data }) => {
    const parsed: unknown = JSON.parse(data);
    if (
      event === 'enterprise-overview-stream-ready' ||
      event === 'enterprise-overview-stream-reset'
    ) {
      if (!isOverviewStreamControl(parsed)) {
        throw new Error('Enterprise Overview stream control is invalid');
      }
      requireMatchingEventId(id, parsed.cursor, 'Enterprise Overview stream');
      acceptedCursor = parsed.cursor;
      callbacks.onControl(parsed);
      return;
    }
    if (event === 'enterprise-overview-change') {
      if (!isOverviewStreamChange(parsed)) {
        throw new Error('Enterprise Overview stream event is invalid');
      }
      requireMatchingEventId(id, parsed.sequence, 'Enterprise Overview stream');
      if (acceptedCursor !== undefined && parsed.sequence <= acceptedCursor) {
        throw new Error('Enterprise Overview stream sequence did not advance');
      }
      acceptedCursor = parsed.sequence;
      callbacks.onChange(parsed);
    }
  });
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
  if (!response.ok) throw await enterpriseError(response);
  return response.json() as Promise<T>;
}

async function enterpriseError(response: Response) {
  const body = await response.json().catch(() => ({
    code: 'UNKNOWN_ERROR',
    message: `Request failed with status ${response.status}`,
    details: {},
    requestId: '',
    timestamp: new Date().toISOString(),
  }));
  return new SessionApiError(response.status, body);
}

function isOverviewStreamControl(
  value: unknown
): value is EnterpriseOverviewStreamControl {
  if (!value || typeof value !== 'object') return false;
  const control = value as Partial<EnterpriseOverviewStreamControl>;
  return (
    typeof control.cursor === 'number' &&
    Number.isSafeInteger(control.cursor) &&
    typeof control.resetRequired === 'boolean' &&
    typeof control.connectedAt === 'string'
  );
}

function isOverviewStreamChange(
  value: unknown
): value is EnterpriseOverviewStreamChange {
  if (!value || typeof value !== 'object') return false;
  const change = value as Partial<EnterpriseOverviewStreamChange>;
  return (
    typeof change.sequence === 'number' &&
    Number.isSafeInteger(change.sequence) &&
    typeof change.changeType === 'string' &&
    OVERVIEW_CHANGE_TYPES.has(
      change.changeType as EnterpriseOverviewChangeType
    ) &&
    typeof change.occurredAt === 'string' &&
    typeof change.replayed === 'boolean'
  );
}
