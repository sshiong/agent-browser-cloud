import type {
  SessionView,
  CreateSessionRequest,
  CreateSessionResponse,
  ApiError,
  OperationResponse,
  SessionListResponse,
  BrowserStateView,
  RemoteDesktopConnection,
  RemoteDesktopParticipantListResponse,
  RemoteDesktopParticipantView,
  StateResyncRequest,
  StateResyncResponse,
  SessionResourceView,
  ResourceEventListResponse,
  ResourcePolicyRequest,
  ResourcePolicyOperationResponse,
  SessionSafePointView,
  SessionMigrationView,
  ResourceStreamControl,
  ResourceStreamEvent,
  CreateSafetyLeaseRequest,
  RenewSafetyLeaseRequest,
  SafetyLeaseView,
  SafetyLeaseListResponse,
  RecoveryContractListResponse,
  RecoveryContractView,
  UpsertRecoveryContractRequest,
  RequestRecoveryContractApprovalRequest,
  RecoveryContractApprovalView,
  RecoveryContractRevisionListResponse,
  RecoveryContractDiffView,
  RestoreRecoveryContractRevisionRequest,
  BusinessRecoveryValidationView,
  ProviderEvidenceListResponse,
  SessionApplicationBindingView,
  RebindSessionApplicationRequest,
  SessionApplicationRebindView,
  SessionEvidenceListResponse,
  EvidencePurpose,
  EvidenceCaptureView,
  EvidenceAccessGrantView,
  RedeemEvidenceAccessResponse,
  ChallengeEventListResponse,
  ChallengePreviewView,
  AuthorizeHumanAssistRequest,
  HumanAssistView,
} from '../types/session';
import type {
  ProxyRebindOperation,
  ProxyRebindRequest,
  ProxyRebindView,
} from '@/types/proxy';
import { getRuntimeIdentity } from '@/auth/runtimeIdentity';
import { consumeEventStream, requireMatchingEventId } from './eventStream';

/**
 * API 基础 URL。
 */
const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');
export const DEFAULT_TENANT_ID =
  import.meta.env.VITE_TENANT_ID?.trim() || 'tenant-local';
export const DEFAULT_ACTOR_ID =
  import.meta.env.VITE_ACTOR_ID?.trim() || 'user-local';

export function currentTenantId() {
  return getRuntimeIdentity()?.tenantId || DEFAULT_TENANT_ID;
}

export function currentActorId() {
  return getRuntimeIdentity()?.actorId || DEFAULT_ACTOR_ID;
}

export function identityHeaders(
  tenantId = DEFAULT_TENANT_ID,
  actorId = DEFAULT_ACTOR_ID
): Record<string, string> {
  const identity = getRuntimeIdentity();
  if (identity?.accessToken) {
    return { Authorization: `Bearer ${identity.accessToken}` };
  }
  return {
    'X-Tenant-Id': identity?.tenantId || tenantId,
    'X-Actor-Id': identity?.actorId || actorId,
    ...(identity?.roles.length ? { 'X-Roles': identity.roles.join(',') } : {}),
  };
}

/**
 * API 错误类。
 */
export class SessionApiError extends Error {
  constructor(
    public status: number,
    public body: ApiError
  ) {
    super(body.message);
    this.name = 'SessionApiError';
  }
}

export function isSessionApiError(error: unknown): error is SessionApiError {
  return error instanceof SessionApiError;
}

/**
 * 发送 API 请求。
 */
async function request<T>(
  path: string,
  options?: RequestInit,
  tenantId?: string,
  actorId?: string
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(tenantId ? identityHeaders(tenantId, actorId) : {}),
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

async function requestOptional<T>(
  path: string,
  options?: RequestInit,
  tenantId?: string,
  actorId?: string
): Promise<T | null> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(tenantId ? identityHeaders(tenantId, actorId) : {}),
      ...options?.headers,
    },
  });

  if (response.status === 204) return null;
  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'UNKNOWN_ERROR',
      message: `Request failed with status ${response.status}`,
    }));
    throw new SessionApiError(response.status, body);
  }
  return response.json();
}

/**
 * 获取 Session 详情。
 */
export async function getSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SessionView> {
  return request<SessionView>(`/sessions/${sessionId}`, { signal }, tenantId);
}

/**
 * 获取 Session 最近一次由 Browser Node 采集的浏览器状态。
 */
export async function getBrowserState(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<BrowserStateView | null> {
  return requestOptional<BrowserStateView>(
    `/sessions/${sessionId}/state`,
    { signal },
    tenantId
  );
}

export async function getSessionResources(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SessionResourceView> {
  return request<SessionResourceView>(
    `/sessions/${sessionId}/resources`,
    { signal },
    tenantId
  );
}

export async function getSessionResourceEvents(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ResourceEventListResponse> {
  return request<ResourceEventListResponse>(
    `/sessions/${sessionId}/resource-events?limit=50`,
    { signal },
    tenantId
  );
}

export async function getSessionEvidence(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SessionEvidenceListResponse> {
  return request<SessionEvidenceListResponse>(
    `/sessions/${sessionId}/evidence?limit=20`,
    { signal },
    tenantId
  );
}

export async function captureSessionEvidence(
  sessionId: string,
  purpose: EvidencePurpose,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<EvidenceCaptureView> {
  return request<EvidenceCaptureView>(
    `/sessions/${sessionId}/evidence:capture`,
    {
      method: 'POST',
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ purpose }),
    },
    tenantId
  );
}

export async function getSessionEvidenceCapture(
  sessionId: string,
  captureId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<EvidenceCaptureView> {
  return request<EvidenceCaptureView>(
    `/sessions/${sessionId}/evidence-captures/${captureId}`,
    { signal },
    tenantId
  );
}

export async function createSessionEvidenceAccessGrant(
  sessionId: string,
  evidenceId: string,
  purpose: EvidencePurpose,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<EvidenceAccessGrantView> {
  return request<EvidenceAccessGrantView>(
    `/sessions/${sessionId}/evidence/${evidenceId}/access-grants`,
    {
      method: 'POST',
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ purpose }),
    },
    tenantId
  );
}

export async function redeemSessionEvidenceAccessGrant(
  sessionId: string,
  grantId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<RedeemEvidenceAccessResponse> {
  return request<RedeemEvidenceAccessResponse>(
    `/sessions/${sessionId}/evidence-access-grants/${grantId}:redeem`,
    { method: 'POST', signal },
    tenantId
  );
}

export async function getSessionSafePoint(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SessionSafePointView> {
  return request<SessionSafePointView>(
    `/sessions/${sessionId}/safe-point`,
    { signal },
    tenantId
  );
}

export async function acquireSessionSafetyLease(
  sessionId: string,
  data: CreateSafetyLeaseRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SafetyLeaseView> {
  return request<SafetyLeaseView>(
    `/sessions/${sessionId}/safety-leases`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function listSessionSafetyLeases(
  sessionId: string,
  limit = 50,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SafetyLeaseListResponse> {
  return request<SafetyLeaseListResponse>(
    `/sessions/${sessionId}/safety-leases?limit=${Math.max(1, Math.min(limit, 100))}`,
    { signal },
    tenantId
  );
}

export async function renewSessionSafetyLease(
  sessionId: string,
  leaseId: string,
  data: RenewSafetyLeaseRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SafetyLeaseView> {
  return request<SafetyLeaseView>(
    `/sessions/${sessionId}/safety-leases/${leaseId}`,
    {
      method: 'PUT',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function releaseSessionSafetyLease(
  sessionId: string,
  leaseId: string,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SafetyLeaseView> {
  return request<SafetyLeaseView>(
    `/sessions/${sessionId}/safety-leases/${leaseId}:release`,
    {
      method: 'POST',
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function getSessionMigration(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<SessionMigrationView | null> {
  return requestOptional<SessionMigrationView>(
    `/sessions/${sessionId}/migration`,
    { signal },
    tenantId
  );
}

export async function getSessionProxyRebind(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ProxyRebindView | null> {
  return requestOptional<ProxyRebindView>(
    `/sessions/${sessionId}/proxy-rebind`,
    { signal },
    tenantId
  );
}

export async function rebindSessionProxy(
  sessionId: string,
  data: ProxyRebindRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID
): Promise<ProxyRebindOperation> {
  return request<ProxyRebindOperation>(
    `/sessions/${sessionId}/proxy-binding:rebind`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

type SessionStreamCallbacks = {
  sessionId: string;
  tenantId?: string;
  lastEventId?: string;
  signal: AbortSignal;
  onOpen: () => void;
  onControl: (control: ResourceStreamControl) => void;
  onChange: (change: ResourceStreamEvent) => void;
};

export async function streamSessionResourceChanges(
  callbacks: SessionStreamCallbacks
): Promise<void> {
  return streamSessionChangesFromEndpoint(
    callbacks,
    'resource-stream',
    ['resource-stream-ready', 'resource-stream-reset'],
    'session-resource-change'
  );
}

export async function streamSessionChanges(
  callbacks: SessionStreamCallbacks
): Promise<void> {
  return streamSessionChangesFromEndpoint(
    callbacks,
    'event-stream',
    ['session-stream-ready', 'session-stream-reset'],
    'session-change'
  );
}

async function streamSessionChangesFromEndpoint(
  {
    sessionId,
    tenantId = DEFAULT_TENANT_ID,
    lastEventId,
    signal,
    onOpen,
    onControl,
    onChange,
  }: SessionStreamCallbacks,
  endpoint: 'resource-stream' | 'event-stream',
  controlEvents: readonly string[],
  changeEvent: 'session-resource-change' | 'session-change'
): Promise<void> {
  const response = await fetch(
    `${API_BASE}/sessions/${sessionId}/${endpoint}`,
    {
      signal,
      headers: {
        Accept: 'text/event-stream',
        ...identityHeaders(tenantId),
        ...(lastEventId ? { 'Last-Event-ID': lastEventId } : {}),
      },
    }
  );
  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'SESSION_STREAM_UNAVAILABLE',
      message: `Session stream failed with status ${response.status}`,
    }));
    throw new SessionApiError(response.status, body);
  }
  if (!response.body) {
    throw new Error('Session stream response body is unavailable');
  }
  onOpen();
  let acceptedCursor: number | undefined;
  await consumeEventStream(response.body, ({ id, event, data }) => {
    const parsed: unknown = JSON.parse(data);
    if (controlEvents.includes(event)) {
      if (!isResourceStreamControl(parsed)) {
        throw new Error('Resource stream control event is invalid');
      }
      requireMatchingEventId(id, parsed.cursor);
      acceptedCursor = parsed.cursor;
      onControl(parsed);
      return;
    }
    if (event === changeEvent) {
      if (!isResourceStreamEvent(parsed)) {
        throw new Error('Resource stream change event is invalid');
      }
      requireMatchingEventId(id, parsed.sequence);
      if (acceptedCursor !== undefined && parsed.sequence <= acceptedCursor) {
        throw new Error('Resource stream sequence did not advance');
      }
      acceptedCursor = parsed.sequence;
      onChange(parsed);
    }
  });
}

function isResourceStreamControl(
  value: unknown
): value is ResourceStreamControl {
  if (!value || typeof value !== 'object') return false;
  const control = value as Partial<ResourceStreamControl>;
  return (
    typeof control.cursor === 'number' &&
    Number.isSafeInteger(control.cursor) &&
    typeof control.resetRequired === 'boolean' &&
    typeof control.connectedAt === 'string'
  );
}

function isResourceStreamEvent(value: unknown): value is ResourceStreamEvent {
  if (!value || typeof value !== 'object') return false;
  const change = value as Partial<ResourceStreamEvent>;
  return (
    typeof change.sequence === 'number' &&
    Number.isSafeInteger(change.sequence) &&
    (change.changeType === 'RESOURCE_SAMPLE' ||
      change.changeType === 'RESOURCE_EVENT' ||
      change.changeType === 'SAFETY_LEASE_EVENT' ||
      change.changeType === 'SESSION' ||
      change.changeType === 'BROWSER_STATE' ||
      change.changeType === 'AUDIT_EVENT' ||
      change.changeType === 'OPERATION' ||
      change.changeType === 'AGENT_TASK') &&
    typeof change.entityId === 'string' &&
    typeof change.occurredAt === 'string' &&
    typeof change.replayed === 'boolean'
  );
}

export async function updateSessionResourcePolicy(
  sessionId: string,
  policy: ResourcePolicyRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ResourcePolicyOperationResponse> {
  return request<ResourcePolicyOperationResponse>(
    `/sessions/${sessionId}/resource-policy`,
    {
      method: 'PATCH',
      body: JSON.stringify(policy),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function resyncBrowserState(
  sessionId: string,
  data: StateResyncRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<StateResyncResponse> {
  return request<StateResyncResponse>(
    `/sessions/${sessionId}:resync-state`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function listRecoveryContracts(
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<RecoveryContractListResponse> {
  return request<RecoveryContractListResponse>(
    '/applications/recovery-contracts',
    { signal },
    tenantId
  );
}

export async function upsertRecoveryContract(
  applicationId: string,
  data: UpsertRecoveryContractRequest,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<RecoveryContractView> {
  return request<RecoveryContractView>(
    `/applications/${encodeURIComponent(applicationId)}/recovery-contract`,
    {
      method: 'PUT',
      body: JSON.stringify(data),
      signal,
    },
    tenantId
  );
}

export async function listRecoveryContractRevisions(
  applicationId: string,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<RecoveryContractRevisionListResponse> {
  return request<RecoveryContractRevisionListResponse>(
    `/applications/${encodeURIComponent(applicationId)}/recovery-contract/revisions`,
    { signal },
    tenantId
  );
}

export async function getRecoveryContractDiff(
  applicationId: string,
  fromVersion: number,
  toVersion: number,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<RecoveryContractDiffView> {
  const query = new URLSearchParams({
    compareToVersion: String(toVersion),
  });
  return request<RecoveryContractDiffView>(
    `/applications/${encodeURIComponent(applicationId)}/recovery-contract/revisions/${fromVersion}/diff?${query.toString()}`,
    { signal },
    tenantId
  );
}

export async function restoreRecoveryContractRevision(
  applicationId: string,
  data: RestoreRecoveryContractRevisionRequest,
  idempotencyKey: string,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<RecoveryContractView> {
  return request<RecoveryContractView>(
    `/applications/${encodeURIComponent(applicationId)}/recovery-contract:restore`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function requestRecoveryContractApproval(
  applicationId: string,
  data: RequestRecoveryContractApprovalRequest,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<RecoveryContractApprovalView> {
  return request<RecoveryContractApprovalView>(
    `/applications/${encodeURIComponent(applicationId)}/recovery-contract:request-approval`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
    },
    tenantId
  );
}

export async function decideRecoveryContractApproval(
  applicationId: string,
  approvalId: string,
  decision: 'approve' | 'reject',
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<RecoveryContractApprovalView> {
  return request<RecoveryContractApprovalView>(
    `/applications/${encodeURIComponent(applicationId)}/recovery-contract-approvals/${encodeURIComponent(approvalId)}:${decision}`,
    { method: 'POST', signal },
    tenantId
  );
}

export async function getBusinessRecovery(
  sessionId: string,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<BusinessRecoveryValidationView | null> {
  return requestOptional<BusinessRecoveryValidationView>(
    `/sessions/${sessionId}/business-recovery`,
    { signal },
    tenantId
  );
}

export async function validateBusinessRecovery(
  sessionId: string,
  idempotencyKey: string,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<BusinessRecoveryValidationView> {
  return request<BusinessRecoveryValidationView>(
    `/sessions/${sessionId}/business-recovery:validate`,
    {
      method: 'POST',
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export async function getBusinessRecoveryProviderEvidence(
  sessionId: string,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<ProviderEvidenceListResponse> {
  return request<ProviderEvidenceListResponse>(
    `/sessions/${sessionId}/business-recovery/provider-evidence`,
    { signal },
    tenantId
  );
}

export async function getSessionApplicationBinding(
  sessionId: string,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<SessionApplicationBindingView | null> {
  return requestOptional<SessionApplicationBindingView>(
    `/sessions/${sessionId}/application-binding`,
    { signal },
    tenantId
  );
}

export async function rebindSessionApplication(
  sessionId: string,
  data: RebindSessionApplicationRequest,
  idempotencyKey: string,
  tenantId = currentTenantId(),
  signal?: AbortSignal
): Promise<SessionApplicationRebindView> {
  return request<SessionApplicationRebindView>(
    `/sessions/${sessionId}/application-binding:rebind`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

/**
 * 创建新 Session。
 */
export async function createSession(
  data: CreateSessionRequest,
  idempotencyKey: string,
  signal?: AbortSignal
): Promise<CreateSessionResponse> {
  return request<CreateSessionResponse>(
    '/sessions',
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    },
    data.tenantId
  );
}

/**
 * 启动 Session。
 */
export async function startSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:start`,
    {
      method: 'POST',
      signal,
    },
    tenantId
  );
}

/**
 * 终止 Session。
 */
export async function terminateSession(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:terminate`,
    {
      method: 'POST',
      signal,
    },
    tenantId
  );
}

export async function requestHumanTakeover(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:takeover`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

export async function releaseHumanTakeover(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
): Promise<OperationResponse> {
  return request<OperationResponse>(
    `/sessions/${sessionId}:release-takeover`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

export async function createRemoteDesktopConnection(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal,
  viewOnly = false
): Promise<RemoteDesktopConnection> {
  return request<RemoteDesktopConnection>(
    `/sessions/${sessionId}:desktop-connection${viewOnly ? '?viewOnly=true' : ''}`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

export async function getRemoteDesktopParticipants(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<RemoteDesktopParticipantListResponse> {
  return request<RemoteDesktopParticipantListResponse>(
    `/sessions/${sessionId}/desktop-participants`,
    { signal },
    tenantId
  );
}

export async function revokeRemoteDesktopParticipant(
  sessionId: string,
  connectionId: string,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId()
): Promise<RemoteDesktopParticipantView> {
  return request<RemoteDesktopParticipantView>(
    `/sessions/${sessionId}/desktop-participants/${connectionId}:revoke`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId,
    actorId
  );
}

export async function getSessionChallenges(
  sessionId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
): Promise<ChallengeEventListResponse> {
  return request<ChallengeEventListResponse>(
    `/sessions/${sessionId}/challenges?limit=20`,
    { signal },
    tenantId
  );
}

export async function getChallengePreview(
  eventId: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
): Promise<ChallengePreviewView> {
  return request<ChallengePreviewView>(
    `/challenges/${eventId}/preview`,
    { signal },
    tenantId,
    actorId
  );
}

export async function authorizeHumanAssist(
  eventId: string,
  body: AuthorizeHumanAssistRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
): Promise<HumanAssistView> {
  return request<HumanAssistView>(
    `/challenges/${eventId}/assist-authorizations`,
    {
      method: 'POST',
      body: JSON.stringify(body),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId,
    actorId
  );
}

/**
 * 列出 Sessions。
 */
export async function listSessions(params?: {
  state?: string;
  query?: string;
  groupId?: string;
  tagIds?: string[];
  tagMatch?: 'ANY' | 'ALL';
  limit?: number;
  offset?: number;
  tenantId?: string;
  signal?: AbortSignal;
}): Promise<SessionListResponse> {
  const searchParams = new URLSearchParams();
  if (params?.state) searchParams.set('state', params.state);
  if (params?.query?.trim()) searchParams.set('q', params.query.trim());
  if (params?.groupId) searchParams.set('groupId', params.groupId);
  params?.tagIds?.forEach((tagId) => searchParams.append('tagId', tagId));
  if (params?.tagIds?.length) {
    searchParams.set('tagMatch', params.tagMatch ?? 'ANY');
  }
  if (params?.limit) searchParams.set('limit', String(params.limit));
  if (params?.offset) searchParams.set('offset', String(params.offset));

  const query = searchParams.toString();
  return request(
    `/sessions${query ? `?${query}` : ''}`,
    { signal: params?.signal },
    params?.tenantId || DEFAULT_TENANT_ID
  );
}
