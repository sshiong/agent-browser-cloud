import {
  currentActorId,
  DEFAULT_ACTOR_ID,
  DEFAULT_TENANT_ID,
  identityHeaders,
  SessionApiError,
} from '@/api/session';
import type {
  AgentTaskListResponse,
  AgentTaskSummaryListResponse,
  AgentTaskView,
  AgentBrowserFindRequest,
  AgentBrowserInspectRequest,
  AgentBrowserSnapshot,
  AgentBrowserTargetList,
  AgentBrowserFileUpload,
  AgentBrowserFileUploadRequest,
  AgentBrowserDownload,
  AgentBrowserDownloadList,
  CreateAgentTaskRequest,
  ExecuteAgentBrowserActionsRequest,
} from '@/types/agent';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(
  path: string,
  options?: RequestInit,
  tenantId = DEFAULT_TENANT_ID,
  actorId = DEFAULT_ACTOR_ID
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    cache: options?.cache ?? (options?.method ? undefined : 'no-store'),
    headers: {
      ...(options?.body instanceof FormData
        ? {}
        : { 'Content-Type': 'application/json' }),
      ...identityHeaders(tenantId, actorId),
      ...options?.headers,
    },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'UNKNOWN_ERROR',
      message: `Request failed with status ${response.status}`,
    }));
    throw new SessionApiError(response.status, body);
  }
  return response.json();
}

export function uploadAgentBrowserFile(
  sessionId: string,
  data: AgentBrowserFileUploadRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
) {
  const form = new FormData();
  form.set('targetRef', data.targetRef);
  form.set('targetRevision', String(data.targetRevision));
  form.set('baseStateVersion', String(data.baseStateVersion));
  form.set('baseContentHash', data.baseContentHash);
  form.set('filename', data.filename);
  form.set('mimeType', data.mimeType || 'application/octet-stream');
  form.set('contentSha256', data.contentSha256);
  form.set('file', data.file, data.filename);
  return request<AgentBrowserFileUpload>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/files/uploads`,
    {
      method: 'POST',
      body: form,
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId,
    actorId
  );
}

export function getAgentBrowserFileUpload(
  sessionId: string,
  uploadId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentBrowserFileUpload>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/files/uploads/${encodeURIComponent(uploadId)}`,
    { signal },
    tenantId
  );
}

export function listAgentBrowserDownloads(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentBrowserDownloadList>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/files/downloads`,
    { signal },
    tenantId
  );
}

export function waitForAgentBrowserDownload(
  sessionId: string,
  downloadId: string,
  timeoutMs = 30_000,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentBrowserDownload>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/files/downloads/${encodeURIComponent(downloadId)}:wait?timeoutMs=${timeoutMs}`,
    { signal },
    tenantId
  );
}

export function listAgentTasks(
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskListResponse>(
    '/agent-tasks?limit=100&offset=0',
    { signal },
    tenantId
  );
}

export function listAgentTaskSummaries(
  limit = 20,
  cursor?: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  const parameters = new URLSearchParams({ limit: String(limit) });
  if (cursor) parameters.set('cursor', cursor);
  return request<AgentTaskSummaryListResponse>(
    `/agent-task-summaries?${parameters.toString()}`,
    { signal },
    tenantId
  );
}

export function getAgentTask(
  taskId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/agent-tasks/${encodeURIComponent(taskId)}`,
    { signal },
    tenantId
  );
}

export function createAgentTask(
  sessionId: string,
  data: CreateAgentTaskRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-tasks`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export function getAgentBrowserSnapshot(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentBrowserSnapshot>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/snapshot`,
    { signal },
    tenantId
  );
}

export function inspectAgentBrowserElements(
  sessionId: string,
  data: AgentBrowserInspectRequest,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentBrowserTargetList>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/inspect`,
    { method: 'POST', body: JSON.stringify(data), signal },
    tenantId
  );
}

export function findAgentBrowserElements(
  sessionId: string,
  data: AgentBrowserFindRequest,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentBrowserTargetList>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/find`,
    { method: 'POST', body: JSON.stringify(data), signal },
    tenantId
  );
}

export function executeAgentBrowserActions(
  sessionId: string,
  data: ExecuteAgentBrowserActionsRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-browser/execute-actions`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export function executeAgentTask(
  taskId: string,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/agent-tasks/${encodeURIComponent(taskId)}:execute`,
    {
      method: 'POST',
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

function humanDecision(
  taskId: string,
  action: 'approve' | 'reject' | 'accept-handoff' | 'reject-handoff',
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/agent-tasks/${encodeURIComponent(taskId)}:${action}`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

export const approveAgentTask = (taskId: string) =>
  humanDecision(taskId, 'approve');
export const rejectAgentTask = (taskId: string) =>
  humanDecision(taskId, 'reject');
export const acceptAgentHandoff = (taskId: string) =>
  humanDecision(taskId, 'accept-handoff');
export const rejectAgentHandoff = (taskId: string) =>
  humanDecision(taskId, 'reject-handoff');
