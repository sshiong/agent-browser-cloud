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

export type ExecutionEnvironment =
  'SYSTEM_MANAGED' | 'CONTAINER' | 'ENHANCED_SANDBOX' | 'MICROVM' | 'NATIVE_OS';

export type MaximumReachedPolicy =
  'PAUSE_AGENT' | 'WAIT_SAFE_POINT_MIGRATE' | 'HIBERNATE' | 'TERMINATE_STRICT';

export type AgentPolicy =
  'DISABLED' | 'RESTRICTED' | 'BALANCED' | 'INTERACTIVE';

export type ResourcePolicyStatus =
  | 'STABLE'
  | 'OBSERVING'
  | 'SCALING_UP'
  | 'SCALING_DOWN'
  | 'AT_MAXIMUM'
  | 'WAITING_SAFE_POINT'
  | 'MIGRATING'
  | 'AGENT_PAUSED'
  | 'HIBERNATING'
  | 'CRITICAL';

export interface ResourcePolicyRequest {
  mode: 'AUTO';
  onMaximumReached?: MaximumReachedPolicy;
  allowMigration?: boolean;
  allowHibernate?: boolean;
  blockMigrationDuringHumanTakeover?: boolean;
  executionEnvironment?: ExecutionEnvironment;
  minimumTemplate?: string;
  maximumCpuMillis?: number;
  maximumMemoryMib?: number;
  maximumCostPerHour?: number;
  scaleUpWindowSeconds?: number;
  scaleDownWindowSeconds?: number;
  adjustmentCooldownSeconds?: number;
}

export interface ResourcePolicyView extends Required<
  Omit<ResourcePolicyRequest, 'maximumCostPerHour'>
> {
  resolvedTemplate: string;
  maximumCostPerHour?: number;
}

/**
 * Session 视图。
 */
export interface WorkspaceTagSummary {
  tagId: string;
  name: string;
  color: string;
}

export interface SessionView {
  sessionId: string;
  displayName: string;
  tenantId: string;
  profileId: string;
  groupId?: string;
  tags?: WorkspaceTagSummary[];
  humanTakeoverEnabled?: boolean;
  agentPolicy?: AgentPolicy;
  extensionIds?: string[];
  region: string;
  resourceClass: ResourceClass;
  state: SessionState;
  nodeId?: string;
  runtimeBuildId?: string;
  proxyBindingId?: string;
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
  runtimeBuildId?: string;
  applicationId?: string;
  groupId?: string;
  tagIds?: string[];
  region?: string;
  resourcePolicy?: ResourcePolicyRequest;
  /** @deprecated Legacy SDK compatibility. Web UI must use resourcePolicy=AUTO. */
  resourceClass?: ResourceClass;
  requestedTabs?: number;
  agentActionsPerMinute?: number;
  remoteDesktop?: boolean;
  humanTakeoverEnabled?: boolean;
  agentPolicy?: AgentPolicy;
  web3Workload?: boolean;
  mediaWorkload?: boolean;
  requestedMediaStreams?: number;
  mediaBitrateKbps?: number;
  extensionIds?: string[];
  metadata?: Record<string, string>;
}

export interface RecoveryTargetIndicator {
  role: string;
  name: string;
}

export type BusinessRecoveryAction =
  | 'NONE'
  | 'RELOAD'
  | 'NAVIGATE_HOME'
  | 'REOPEN_KNOWN_ROUTE'
  | 'REFRESH_SESSION'
  | 'RESTART_EXTENSION';

export interface RecoveryContractView {
  contractId: string;
  applicationId: string;
  version: number;
  expectedOrigins: string[];
  readyRoutePrefixes: string[];
  loginRoutePrefixes: string[];
  requiredTargets: RecoveryTargetIndicator[];
  loginTargets: RecoveryTargetIndicator[];
  permissionDeniedTargets: RecoveryTargetIndicator[];
  accountMismatchTargets: RecoveryTargetIndicator[];
  requiredExtensionIds: string[];
  allowDepthLimited: boolean;
  recoveryAction: BusinessRecoveryAction;
  recoveryExtensionId?: string;
  maximumAutoRecovery: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RecoveryContractListResponse {
  items: RecoveryContractView[];
  total: number;
}

export interface UpsertRecoveryContractRequest {
  expectedVersion: number;
  expectedOrigins: string[];
  readyRoutePrefixes: string[];
  loginRoutePrefixes: string[];
  requiredTargets: RecoveryTargetIndicator[];
  loginTargets: RecoveryTargetIndicator[];
  permissionDeniedTargets: RecoveryTargetIndicator[];
  accountMismatchTargets: RecoveryTargetIndicator[];
  requiredExtensionIds: string[];
  allowDepthLimited: boolean;
  recoveryAction: BusinessRecoveryAction;
  recoveryExtensionId?: string;
  maximumAutoRecovery: number;
  enabled: boolean;
}

export type BusinessRecoveryVerdict =
  | 'READY'
  | 'READY_WITH_WARNING'
  | 'LOGIN_REQUIRED'
  | 'PERMISSION_CHANGED'
  | 'ACCOUNT_MISMATCH'
  | 'APPLICATION_UNAVAILABLE'
  | 'STATE_CHANGED'
  | 'MANUAL_RECOVERY_REQUIRED';

export interface BusinessRecoveryValidationView {
  validationId: string;
  sessionId: string;
  applicationId?: string;
  contractVersion?: number;
  contextEpoch: number;
  stateVersion: number;
  verdict: BusinessRecoveryVerdict;
  ready: boolean;
  evidence: string[];
  source: 'API' | 'MIGRATION';
  requestId: string;
  evaluatedAt: string;
}

/**
 * 创建 Session 响应。
 */
export interface CreateSessionResponse {
  sessionId: string;
  operationId?: string;
  state: 'CREATED';
  resourcePolicy: ResourcePolicyView;
  context: SessionContext;
}

export interface SessionResourceView {
  sessionId: string;
  policy: ResourcePolicyView;
  allocation?: {
    nodeId: string;
    template: string;
    cpuMillis?: number;
    memoryRequestMib?: number;
    memoryLimitMib?: number;
    tabBudget?: number;
    stateCollectorBudgetPercent?: number;
    remoteDesktopBitrateKbps?: number;
    extensionCpuWeight?: number;
    mediaEncoderSlots?: number;
    mediaEncoderSlotLimit?: number;
    backgroundTabsFrozen: boolean;
    newTabsBlocked: boolean;
    placementState: string;
  };
  usage?: {
    cpuPercent?: number;
    memoryRssMib?: number;
    memoryPercentOfLimit?: number;
    memoryPsiSomeAvg10?: number;
    rendererCount?: number;
    tabCount?: number;
    agentActionLatencyMs?: number;
    stateDiffQueueDepth?: number;
    profileIoBytesPerSecond?: number;
    extensionCpuPercent?: number;
    extensionMemoryMib?: number;
    remoteDesktopFrameAgeMs?: number;
    mediaEncoderPercent?: number;
    observedAt: string;
  };
  usageSamples: {
    observedAt: string;
    cpuPercent?: number;
    memoryRssMib?: number;
    memoryPercentOfLimit?: number;
  }[];
  cost?: {
    currentHourlyCost?: number;
    maximumHourlyCost?: number;
    pricingVersion?: string;
    lastEvaluatedAt?: string;
    trend: {
      observedAt: string;
      hourlyCost: number;
      pricingVersion: string;
    }[];
  };
  status: ResourcePolicyStatus;
  statusReason?: string;
  dataFreshness: 'LIVE' | 'STALE' | 'AWAITING_TELEMETRY';
  lastEvaluatedAt?: string;
  lastAdjustedAt?: string;
}

export interface ResourceEventView {
  eventId: string;
  occurredAt: string;
  eventType: string;
  reason: string;
  oldResources?: Record<string, unknown>;
  newResources?: Record<string, unknown>;
  decisionSource: string;
  operationId?: string;
  requestId?: string;
  result: string;
}

export interface ResourceEventListResponse {
  items: ResourceEventView[];
  limit: number;
  offset: number;
}

export interface ResourceStreamEvent {
  sequence: number;
  changeType: 'RESOURCE_SAMPLE' | 'RESOURCE_EVENT' | 'SAFETY_LEASE_EVENT';
  entityId: string;
  occurredAt: string;
  replayed: boolean;
}

export interface ResourceStreamControl {
  cursor: number;
  resetRequired: boolean;
  connectedAt: string;
}

export type ResourceStreamConnectionState =
  'IDLE' | 'CONNECTING' | 'LIVE' | 'RECONNECTING' | 'OFFLINE';

export interface SafePointBlockerView {
  code: string;
  source: string;
  detail: string;
  observedAt?: string;
  expiresAt?: string;
}

export interface SessionSafePointView {
  sessionId: string;
  safe: boolean;
  state: 'SAFE' | 'BLOCKED' | 'UNKNOWN';
  dataFreshness: 'LIVE' | 'STALE' | 'MISSING' | 'NOT_REQUIRED';
  nodeId?: string;
  contextEpoch: number;
  evaluatedAt: string;
  lastNodeObservationAt?: string;
  blockers: SafePointBlockerView[];
}

export type ApplicationSafetySignalType =
  | 'FILE_TRANSFER'
  | 'FORM_SUBMISSION'
  | 'PAYMENT_OR_SECURITY'
  | 'CRITICAL_TRANSACTION'
  | 'BUSINESS_RECOVERY_UNKNOWN';

export interface CreateSafetyLeaseRequest {
  signalType: ApplicationSafetySignalType;
  reasonCode: string;
  ttlSeconds: number;
}

export interface RenewSafetyLeaseRequest {
  ttlSeconds: number;
}

export interface SafetyLeaseView {
  leaseId: string;
  sessionId: string;
  contextEpoch: number;
  signalType: ApplicationSafetySignalType;
  reasonCode: string;
  ownerActorId: string;
  state: 'ACTIVE' | 'RELEASED' | 'EXPIRED';
  acquiredAt: string;
  renewedAt: string;
  expiresAt: string;
  releasedAt?: string;
}

export interface SafetyLeaseListResponse {
  items: SafetyLeaseView[];
  total: number;
}

export interface SessionMigrationView {
  migrationId: string;
  sessionId: string;
  sourceNodeId: string;
  targetNodeId?: string;
  sourceContextEpoch: number;
  targetContextEpoch?: number;
  checkpointId?: string;
  hibernateOperationId?: string;
  restoreOperationId?: string;
  resyncRequestId?: string;
  phase:
    | 'CHECKPOINTING'
    | 'PLACING_TARGET'
    | 'RESTORING'
    | 'STATE_RESYNC'
    | 'BUSINESS_VALIDATION'
    | 'BUSINESS_RECOVERY_ACTION'
    | 'COMPLETED'
    | 'DEGRADED'
    | 'FAILED';
  recoveryResult?: string;
  failureReason?: string;
  autoRecoveryAttempts: number;
  autoRecoveryMaximum: number;
  latestRecoveryAction?: {
    actionId: string;
    migrationId: string;
    attemptNumber: number;
    action: Exclude<BusinessRecoveryAction, 'NONE'>;
    targetUrl?: string;
    targetExtensionId?: string;
    baseStateVersion: number;
    resultingStateVersion?: number;
    state: 'REQUESTED' | 'EXECUTING' | 'ACKNOWLEDGED' | 'COMMITTED' | 'FAILED';
    errorCode?: string;
    createdAt: string;
    completedAt?: string;
  };
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
}

export interface ResourcePolicyOperationResponse {
  operationId: string;
  state: string;
  resourcePolicy: ResourcePolicyView;
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

/**
 * Browser Node 采集并由 Control Plane 持久化的当前浏览器状态。
 */
export interface BrowserStateView {
  sessionId: string;
  contextEpoch: number;
  stateVersion: number;
  targetRevision: number;
  url: string;
  title: string;
  stateHash: string;
  stateQuality: StateQuality;
  targets: InteractiveTargetView[];
}

export interface StateResyncRequest {
  mode: 'FULL' | 'REGION';
  rootRef?: string;
  reason?: string;
}

export interface StateResyncResponse {
  requestId: string;
  mode: StateResyncRequest['mode'];
  state: 'QUEUED';
}

export interface InteractiveTargetView {
  targetRef: string;
  role: string;
  name?: string;
  bounds?: TargetBoundsView;
  enabled: boolean;
  visible: boolean;
  sensitive: boolean;
}

export interface TargetBoundsView {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface RemoteDesktopConnection {
  webSocketPath: string;
  expiresAt: string;
  protocol: 'rfb';
  operationEpoch: number;
  viewOnly: boolean;
}
