import { consumeEventStream, requireMatchingEventId } from './eventStream';
import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';
import type {
  WorkspaceNotificationStreamControl,
  WorkspaceNotificationStreamEvent,
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

export async function streamWorkspaceNotificationChanges(callbacks: {
  lastEventId?: string;
  signal: AbortSignal;
  onOpen: () => void;
  onControl: (control: WorkspaceNotificationStreamControl) => void;
  onChange: (change: WorkspaceNotificationStreamEvent) => void;
}): Promise<void> {
  const response = await fetch(`${API_BASE}/notifications/event-stream`, {
    signal: callbacks.signal,
    headers: {
      Accept: 'text/event-stream',
      ...identityHeaders(DEFAULT_TENANT_ID),
      ...(callbacks.lastEventId
        ? { 'Last-Event-ID': callbacks.lastEventId }
        : {}),
    },
  });
  if (!response.ok) throw await parseApiError(response);
  if (!response.body) {
    throw new Error(
      'Workspace notification stream response body is unavailable'
    );
  }
  callbacks.onOpen();
  let acceptedCursor: number | undefined;
  await consumeEventStream(response.body, ({ id, event, data }) => {
    const parsed: unknown = JSON.parse(data);
    if (
      event === 'notification-stream-ready' ||
      event === 'notification-stream-reset'
    ) {
      if (!isStreamControl(parsed)) {
        throw new Error('Workspace notification stream control is invalid');
      }
      requireMatchingEventId(
        id,
        parsed.cursor,
        'Workspace notification stream'
      );
      acceptedCursor = parsed.cursor;
      callbacks.onControl(parsed);
      return;
    }
    if (event === 'notification-change') {
      if (!isStreamEvent(parsed)) {
        throw new Error('Workspace notification stream event is invalid');
      }
      requireMatchingEventId(
        id,
        parsed.sequence,
        'Workspace notification stream'
      );
      if (acceptedCursor !== undefined && parsed.sequence <= acceptedCursor) {
        throw new Error(
          'Workspace notification stream sequence did not advance'
        );
      }
      acceptedCursor = parsed.sequence;
      callbacks.onChange(parsed);
    }
  });
}

async function parseApiError(response: Response) {
  const body = await response.json().catch(() => ({
    code: 'WORKSPACE_NOTIFICATION_STREAM_UNAVAILABLE',
    message: `Workspace notification stream failed with status ${response.status}`,
    details: {},
    requestId: '',
    timestamp: new Date().toISOString(),
  }));
  return new SessionApiError(response.status, body);
}

function isStreamControl(
  value: unknown
): value is WorkspaceNotificationStreamControl {
  if (!value || typeof value !== 'object') return false;
  const control = value as Partial<WorkspaceNotificationStreamControl>;
  return (
    typeof control.cursor === 'number' &&
    Number.isSafeInteger(control.cursor) &&
    control.cursor >= 0 &&
    typeof control.resetRequired === 'boolean' &&
    typeof control.connectedAt === 'string'
  );
}

function isStreamEvent(
  value: unknown
): value is WorkspaceNotificationStreamEvent {
  if (!value || typeof value !== 'object') return false;
  const change = value as Partial<WorkspaceNotificationStreamEvent>;
  return (
    typeof change.sequence === 'number' &&
    Number.isSafeInteger(change.sequence) &&
    change.sequence > 0 &&
    typeof change.occurredAt === 'string' &&
    typeof change.replayed === 'boolean'
  );
}
