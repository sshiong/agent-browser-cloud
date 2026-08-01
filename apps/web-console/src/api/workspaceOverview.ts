import { consumeEventStream, requireMatchingEventId } from './eventStream';
import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  WorkspaceOverview,
  WorkspaceOverviewChangeType,
  WorkspaceOverviewEvent,
  WorkspaceOverviewStreamControl,
} from '@/types/workspaceOverview';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');
const CHANGE_TYPES = new Set<WorkspaceOverviewChangeType>([
  'SESSION',
  'OPERATION',
  'AGENT_TASK',
  'RESOURCE_EVENT',
  'BROWSER_NODE',
  'PROXY',
  'COST',
  'SECURITY',
]);

export async function getWorkspaceOverview(
  signal?: AbortSignal
): Promise<WorkspaceOverview> {
  const response = await fetch(`${API_BASE}/workspace-overview`, {
    signal,
    headers: {
      Accept: 'application/json',
      ...identityHeaders(DEFAULT_TENANT_ID),
    },
  });
  if (!response.ok) throw await apiError(response);
  return response.json() as Promise<WorkspaceOverview>;
}

export async function streamWorkspaceOverviewChanges(callbacks: {
  lastEventId?: string;
  signal: AbortSignal;
  onOpen: () => void;
  onControl: (control: WorkspaceOverviewStreamControl) => void;
  onChange: (change: WorkspaceOverviewEvent) => void;
}): Promise<void> {
  const response = await fetch(`${API_BASE}/workspace-overview/event-stream`, {
    signal: callbacks.signal,
    headers: {
      Accept: 'text/event-stream',
      ...identityHeaders(DEFAULT_TENANT_ID),
      ...(callbacks.lastEventId
        ? { 'Last-Event-ID': callbacks.lastEventId }
        : {}),
    },
  });
  if (!response.ok) throw await apiError(response);
  if (!response.body) {
    throw new Error('Workspace Overview stream response body is unavailable');
  }
  callbacks.onOpen();
  let acceptedCursor: number | undefined;
  await consumeEventStream(response.body, ({ id, event, data }) => {
    const parsed: unknown = JSON.parse(data);
    if (
      event === 'workspace-overview-stream-ready' ||
      event === 'workspace-overview-stream-reset'
    ) {
      if (!isControl(parsed))
        throw new Error('Overview stream control is invalid');
      requireMatchingEventId(id, parsed.cursor, 'Workspace Overview stream');
      acceptedCursor = parsed.cursor;
      callbacks.onControl(parsed);
      return;
    }
    if (event === 'workspace-overview-change') {
      if (!isEvent(parsed)) throw new Error('Overview stream event is invalid');
      requireMatchingEventId(id, parsed.sequence, 'Workspace Overview stream');
      if (acceptedCursor !== undefined && parsed.sequence <= acceptedCursor) {
        throw new Error('Overview stream sequence did not advance');
      }
      acceptedCursor = parsed.sequence;
      callbacks.onChange(parsed);
    }
  });
}

async function apiError(response: Response) {
  const body = await response.json().catch(() => ({
    code: 'WORKSPACE_OVERVIEW_UNAVAILABLE',
    message: `Workspace Overview failed with status ${response.status}`,
    details: {},
    requestId: '',
    timestamp: new Date().toISOString(),
  }));
  return new SessionApiError(response.status, body);
}

function isControl(value: unknown): value is WorkspaceOverviewStreamControl {
  if (!value || typeof value !== 'object') return false;
  const control = value as Partial<WorkspaceOverviewStreamControl>;
  return (
    typeof control.cursor === 'number' &&
    Number.isSafeInteger(control.cursor) &&
    typeof control.resetRequired === 'boolean' &&
    typeof control.connectedAt === 'string'
  );
}

function isEvent(value: unknown): value is WorkspaceOverviewEvent {
  if (!value || typeof value !== 'object') return false;
  const change = value as Partial<WorkspaceOverviewEvent>;
  return (
    typeof change.sequence === 'number' &&
    Number.isSafeInteger(change.sequence) &&
    typeof change.changeType === 'string' &&
    CHANGE_TYPES.has(change.changeType as WorkspaceOverviewChangeType) &&
    typeof change.occurredAt === 'string' &&
    typeof change.replayed === 'boolean'
  );
}
