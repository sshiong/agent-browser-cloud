import type { ProxyRoutingDecision } from '@/types/proxy';

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
  resourceTemplate: string;
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
  resourceTemplate: string;
  state: SessionState;
  nodeId?: string;
  runtimeBuildId?: string;
  proxyBindingId?: string;
  proxyBindingProfileId?: string;
  proxyRoutingDecision?: ProxyRoutingDecision | null;
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
  proxyBindingProfileId?: string;
  resourcePolicy?: ResourcePolicyRequest;
  requestedTabs?: number;
  agentActionsPerMinute?: number;
  remoteDesktop?: boolean;
  humanTakeoverEnabled?: boolean;
  agentPolicy?: AgentPolicy;
  web3Workload?: boolean;
  mediaWorkload?: boolean;
  requestedMediaStreams?: number;
  mediaBitrateKbps?: number;
  videoRecording?: boolean;
  extensionIds?: string[];
  metadata?: Record<string, string>;
}

export interface RecoveryTargetIndicator {
  role: string;
  name: string;
}

export type ProviderEvidenceType =
  'ACCOUNT' | 'TENANT_WORKSPACE' | 'PERMISSION' | 'BUSINESS_ENTITY';

export interface ProviderEvidenceRequirement {
  type: ProviderEvidenceType;
  key: string;
  providerId: string;
  expectedValueHash: string;
  maxAgeSeconds: number;
}

export type BusinessRecoveryAction =
  | 'NONE'
  | 'RELOAD'
  | 'NAVIGATE_HOME'
  | 'REOPEN_KNOWN_ROUTE'
  | 'REFRESH_SESSION'
  | 'RESTART_EXTENSION';

export type RecoveryContractApprovalState =
  'DRAFT' | 'REQUESTED' | 'APPROVED' | 'REJECTED';

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
  requiredProviderEvidence?: ProviderEvidenceRequirement[];
  requireDocumentComplete: boolean;
  minimumNetworkQuietMillis: number;
  transientBlockerTargets: RecoveryTargetIndicator[];
  paymentSecurityRoutePrefixes: string[];
  criticalTransactionRoutePrefixes: string[];
  allowDepthLimited: boolean;
  recoveryAction: BusinessRecoveryAction;
  recoveryExtensionId?: string;
  maximumAutoRecovery: number;
  enabled: boolean;
  approvalState?: RecoveryContractApprovalState;
  approvalId?: string;
  approvalRequestedBy?: string;
  approvedBy?: string;
  approvalRequestedAt?: string;
  approvalDecidedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface RecoveryContractListResponse {
  items: RecoveryContractView[];
  total: number;
}

export interface RecoveryContractRevisionListResponse {
  items: RecoveryContractView[];
  total: number;
  currentVersion: number;
}

export interface RecoveryContractFieldChange {
  field: string;
  changeType: 'MODIFIED';
  beforeValue: string;
  afterValue: string;
}

export interface RecoveryContractDiffView {
  contractId: string;
  applicationId: string;
  fromVersion: number;
  toVersion: number;
  changes: RecoveryContractFieldChange[];
  total: number;
}

export interface RestoreRecoveryContractRevisionRequest {
  expectedCurrentVersion: number;
  sourceContractVersion: number;
  reason: string;
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
  requiredProviderEvidence?: ProviderEvidenceRequirement[];
  requireDocumentComplete?: boolean;
  minimumNetworkQuietMillis?: number;
  transientBlockerTargets?: RecoveryTargetIndicator[];
  paymentSecurityRoutePrefixes?: string[];
  criticalTransactionRoutePrefixes?: string[];
  allowDepthLimited: boolean;
  recoveryAction: BusinessRecoveryAction;
  recoveryExtensionId?: string;
  maximumAutoRecovery: number;
  enabled: boolean;
}

export interface RequestRecoveryContractApprovalRequest {
  expectedVersion: number;
  reason: string;
}

export interface RecoveryContractApprovalView {
  approvalId: string;
  contractId: string;
  applicationId: string;
  contractVersion: number;
  reason: string;
  state: Exclude<RecoveryContractApprovalState, 'DRAFT'>;
  requestedBy: string;
  approvedBy?: string;
  rejectedBy?: string;
  requestedAt: string;
  decidedAt?: string;
  evidenceHash?: string;
}

export interface SessionApplicationBindingView {
  sessionId: string;
  applicationId: string;
  contractId: string;
  contractVersion: number;
  latestContractVersion: number;
  latestApprovalState: RecoveryContractApprovalState;
  currentContractEnabled: boolean;
  upgradeAvailable: boolean;
  boundAt: string;
}

export interface RebindSessionApplicationRequest {
  expectedCurrentVersion: number;
  targetContractVersion: number;
}

export interface SessionApplicationRebindView {
  operationId: string;
  sessionId: string;
  applicationId: string;
  contractId: string;
  previousContractVersion: number;
  targetContractVersion: number;
  state: 'COMMITTED';
  requestId: string;
  createdAt: string;
  completedAt: string;
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

export interface ProviderEvidenceView {
  evidenceId: string;
  sessionId: string;
  applicationId: string;
  contractVersion: number;
  contextEpoch: number;
  stateVersion: number;
  type: ProviderEvidenceType;
  key: string;
  providerId: string;
  outcome: 'MATCH' | 'MISMATCH' | 'UNKNOWN';
  valueHashMatched: boolean;
  providerReferenceHash: string;
  adapterActorId: string;
  requestId: string;
  observedAt: string;
  expiresAt: string;
  createdAt: string;
}

export interface ProviderEvidenceListResponse {
  items: ProviderEvidenceView[];
  total: number;
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
    pausedExtensionIds?: string[];
    successTraceSamplePercent?: number;
    successScreenshotSamplePercent?: number;
    observerFrameRateFps?: number;
    videoRecordingRequested?: boolean;
    videoRecordingEnabled?: boolean;
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
  currentAdjustment?: {
    operationId: string;
    state:
      | 'REQUESTED'
      | 'EXECUTING'
      | 'ACKNOWLEDGED'
      | 'COMMITTED'
      | 'FAILED'
      | 'RECONCILED';
    reason: string;
    failureCode?: string;
    oldResources: Record<string, unknown>;
    requestedResources: Record<string, unknown>;
    requestedAt: string;
    executingAt?: string;
    acknowledgedAt?: string;
    completedAt?: string;
    reconciliationOperationId?: string;
    reconciledAt?: string;
    updatedAt: string;
  };
  status: ResourcePolicyStatus;
  statusReason?: string;
  dataFreshness: 'LIVE' | 'STALE' | 'AWAITING_TELEMETRY';
  lastEvaluatedAt?: string;
  lastAdjustedAt?: string;
}

export interface SessionEvidenceView {
  evidenceId: string;
  evidenceKind:
    | 'AGENT_ACTION_SUCCESS'
    | 'AGENT_ACTION_FAILURE'
    | 'AGENT_NAVIGATION_SUCCESS'
    | 'AGENT_NAVIGATION_FAILURE'
    | 'OBSERVER_MANUAL';
  taskId: string;
  stepId: string;
  commandId: string;
  mandatory: boolean;
  result: 'COMMITTED' | 'FAILED';
  contentSha256?: string;
  contentBytes: number;
  capturedAt: string;
  errorCode?: string;
  redactionState:
    'LEGACY_UNVERIFIED' | 'MASKED' | 'NOT_REQUIRED' | 'FAILED_CLOSED';
  redactedRegionCount: number;
}

export interface SessionEvidenceListResponse {
  items: SessionEvidenceView[];
  limit: number;
  offset: number;
}

export type EvidencePurpose =
  | 'INCIDENT_RESPONSE'
  | 'CHANGE_VALIDATION'
  | 'SUPPORT_DIAGNOSTICS'
  | 'COMPLIANCE_AUDIT';

export interface EvidenceCaptureView {
  captureId: string;
  sessionId: string;
  purpose: EvidencePurpose;
  state: 'EXECUTING' | 'COMMITTED' | 'FAILED';
  evidenceId?: string;
  errorCode?: string;
  commandId: string;
  requestId?: string;
  createdAt: string;
  completedAt?: string;
}

export interface EvidenceAccessGrantView {
  grantId: string;
  sessionId: string;
  evidenceId: string;
  purpose: EvidencePurpose;
  state: 'ISSUED' | 'REDEEMING' | 'REDEEMED' | 'FAILED';
  expiresAt: string;
  createdAt: string;
  redeemedAt?: string;
  errorCode?: string;
  requestId?: string;
}

export interface RedeemEvidenceAccessResponse {
  grantId: string;
  evidenceId: string;
  downloadUrl: string;
  expiresAt: string;
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
  changeType:
    | 'SESSION'
    | 'BROWSER_STATE'
    | 'AUDIT_EVENT'
    | 'OPERATION'
    | 'AGENT_TASK'
    | 'RESOURCE_SAMPLE'
    | 'RESOURCE_EVENT'
    | 'SAFETY_LEASE_EVENT'
    | 'CHALLENGE_EVENT'
    | 'HUMAN_ASSIST_INTENT';
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
  targetCleanupOperationId?: string;
  targetAttempt: number;
  maximumTargetAttempts: number;
  failedTargetNodeIds: string[];
  lastTargetFailureReason?: string;
  resyncRequestId?: string;
  phase:
    | 'CHECKPOINTING'
    | 'PLACING_TARGET'
    | 'RESTORING'
    | 'TARGET_CLEANUP'
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
  documentReadyState: 'loading' | 'interactive' | 'complete' | '';
  networkQuietMillis: number;
  networkEvidenceFresh: boolean;
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
  connectionId: string;
  webSocketPath: string;
  expiresAt: string;
  protocol: 'rfb';
  operationEpoch: number;
  viewOnly: boolean;
  actorBitrateLimitKbps?: number;
  actorFrameRateLimitFps?: number;
}

export interface RemoteDesktopParticipantView {
  connectionId: string;
  sessionId: string;
  contextEpoch: number;
  actorId?: string;
  accessMode?: 'COLLABORATIVE' | 'EXCLUSIVE_TAKEOVER';
  viewOnly?: boolean;
  state: 'CONNECTED' | 'REVOKE_REQUESTED' | 'REVOKED' | 'DISCONNECTED';
  reason: string;
  connectedAt?: string;
  disconnectedAt?: string;
  revokedBy?: string;
  revokeRequestedAt?: string;
  observedAt: string;
  updatedAt: string;
  forwardedBytes: number;
  quotaWaitMillis: number;
  throttledBatches: number;
  egressCostUsd: number;
  unpricedForwardedBytes: number;
  lastCostPricingVersion?: string;
  lastEgressGibUsd?: number;
}

export interface RemoteDesktopParticipantListResponse {
  items: RemoteDesktopParticipantView[];
  onlineCount: number;
}

export interface RemoteDesktopParticipantHistoryPage {
  items: RemoteDesktopParticipantView[];
  total: number;
  limit: number;
  nextCursor?: string;
  hasMore: boolean;
}

export type ChallengeType =
  | 'SINGLE_CLICK'
  | 'IMAGE_SELECTION'
  | 'PUZZLE'
  | 'OTP'
  | 'DEVICE_CONFIRMATION'
  | 'MULTI_ROUND'
  | 'USER_JUDGMENT'
  | 'PAYMENT_CONFIRMATION'
  | 'UNKNOWN';

export type ChallengeStatus =
  | 'SUSPECTED'
  | 'CONFIRMED'
  | 'AUTHORIZED'
  | 'EXECUTING'
  | 'RESOLVED'
  | 'FAILED'
  | 'EXPIRED'
  | 'SUPERSEDED'
  | 'TAKEOVER_REQUIRED';

export interface ChallengeRegion {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ChallengeEventView {
  challengeEventId: string;
  sessionId: string;
  contextEpoch: number;
  stateVersion: number;
  targetRevision: number;
  confidence: number;
  evidence: Record<string, unknown>;
  suspectedType: ChallengeType;
  accessOutcome: 'CHALLENGE_SUSPECTED' | 'CHALLENGE_CONFIRMED';
  targetRef?: string;
  targetSummary: string;
  status: ChallengeStatus;
  oneClickEligible: boolean;
  detectedAt: string;
  authorizationDeadline: string;
  expiresAt: string;
  updatedAt: string;
}

export interface ChallengeEventListResponse {
  items: ChallengeEventView[];
}

export interface ChallengePreviewView {
  challenge: ChallengeEventView;
  previewHash: string;
  highlight?: ChallengeRegion;
  fresh: boolean;
  canAuthorize: boolean;
  blockingReason?: string;
  previewedAt: string;
}

export interface AuthorizeHumanAssistRequest {
  previewHash: string;
  expectedStateVersion: number;
  expectedTargetRevision: number;
}

export interface HumanAssistView {
  intentId: string;
  challengeEventId: string;
  sessionId: string;
  userId: string;
  contextEpoch: number;
  stateVersion: number;
  targetRevision: number;
  allowedTargetRef: string;
  allowedActionCount: 1;
  consumedCount: 0 | 1;
  authorizationEventId: string;
  operationId?: string;
  requestId: string;
  state: 'AUTHORIZED' | 'EXECUTING' | 'COMMITTED' | 'FAILED' | 'EXPIRED';
  expiresAt: string;
  createdAt: string;
  consumedAt?: string;
  completedAt?: string;
  errorCode?: string;
}
