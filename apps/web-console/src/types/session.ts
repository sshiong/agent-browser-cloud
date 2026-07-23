/**
 * Session 上下文。
 */
export interface SessionContext {
  sessionId: string;
  tenantId: string;
  profileId: string;
  nodeId?: string;
  runtimeBuildId?: string;
  isolationProfileId?: string;
  proxyBindingId?: string;
  coordinatorTerm: number;
  contextEpoch: number;
  browserGeneration: number;
  networkRevision: number;
  resourceClass: ResourceClass;
  state: SessionState;
  policyHash: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Session 状态。
 */
export type SessionState =
  | 'CREATED'
  | 'STARTING'
  | 'RUNNING'
  | 'DEGRADED'
  | 'HIBERNATING'
  | 'HIBERNATED'
  | 'RECOVERING'
  | 'TERMINATING'
  | 'TERMINATED'
  | 'FAILED';

/**
 * 资源等级。
 */
export type ResourceClass = 'L0' | 'L1' | 'L2' | 'L3' | 'L4' | 'L5';

/**
 * Session 视图。
 */
export interface SessionView {
  sessionId: string;
  displayName: string;
  tenantId: string;
  profileId: string;
  region: string;
  resourceClass: ResourceClass;
  state: SessionState;
  nodeId?: string;
  runtimeBuildId?: string;
  contextEpoch: number;
  browserGeneration: number;
  currentOperation?: OperationView;
  createdAt: string;
  updatedAt: string;
}

/**
 * 操作视图。
 */
export interface OperationView {
  operationId: string;
  ownerType: 'AGENT' | 'HUMAN' | 'SYSTEM';
  actorId?: string;
  mode: string;
  priority: number;
  coordinatorTerm: number;
  contextEpoch: number;
  operationEpoch: number;
  workflowId?: string;
  cancellable: boolean;
  preemptible: boolean;
  phase: string;
  state: string;
  allowedCapabilities: string[];
  deadline: string;
}

/**
 * 创建 Session 请求。
 */
export interface CreateSessionRequest {
  tenantId: string;
  profileId: string;
  region?: string;
  resourceClass?: ResourceClass;
  metadata?: Record<string, string>;
}

/**
 * 创建 Session 响应。
 */
export interface CreateSessionResponse {
  sessionId: string;
  context: SessionContext;
}

/**
 * Session 状态视图。
 */
export interface SessionStateView {
  sessionId: string;
  currentStateVersion: number;
  currentStateHash: string;
  stateQuality: StateQuality;
  browserGeneration: number;
  contextEpoch: number;
  targetRevision: number;
  networkRevision: number;
  lastCheckpointId?: string;
}

/**
 * 状态质量。
 */
export type StateQuality =
  | 'COMPLETE'
  | 'DEPTH_LIMITED'
  | 'RESYNCING'
  | 'DEGRADED'
  | 'INVALID'
  | 'VISION_REQUIRED'
  | 'HUMAN_REQUIRED';

/**
 * API 错误。
 */
export interface ApiError {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  requestId?: string;
  timestamp?: string;
}

export interface SessionListResponse {
  items: SessionView[];
  total: number;
  limit: number;
  offset: number;
}

export interface OperationResponse {
  operationId: string;
  state: string;
}
