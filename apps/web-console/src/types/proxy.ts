export interface ProxyProviderView {
  providerId: string;
  type: string;
  endpoint: string;
  expectedExitIp: string;
  directFallbackAllowed: boolean;
  state: 'CONFIGURED' | 'CATALOG_CONFIGURED' | 'UNCONFIGURED';
  regions: string[];
  costPerGibUsd: number;
  reputationScore: number;
  maxConcurrentSessions: number;
}

export interface ProxyAllocationView {
  allocationId: string;
  sessionId: string;
  providerId: string;
  protocol: string;
  state: 'ALLOCATED' | 'BOUND' | 'RELEASED' | 'FAILED';
  exitIp: string | null;
  country: string | null;
  asn: string | null;
  allocatedAt: string;
  verifiedAt: string | null;
  releasedAt: string | null;
  updatedAt: string;
}

export interface ProxyOverviewResponse {
  provider: ProxyProviderView;
  providers: ProxyProviderView[];
  allocations: ProxyAllocationView[];
  total: number;
}

export type ProxyBindingHealth =
  'UNVERIFIED' | 'HEALTHY' | 'UNHEALTHY' | 'DISABLED';

export interface ProxyBindingView {
  bindingProfileId: string;
  name: string;
  description: string | null;
  providerId: string;
  region: string | null;
  expectedExitIp: string;
  credentialConfigured: boolean;
  enabled: boolean;
  healthState: ProxyBindingHealth;
  lastVerifiedExitIp: string | null;
  lastHealthCheckedAt: string | null;
  lastFailureReason: string | null;
  probeSampleCount: number;
  probeSuccessRatePercent: number | null;
  latencyEwmaMs: number | null;
  qualityScore: number | null;
  costPerGibUsd: number;
  reputationScore: number;
  maxConcurrentSessions: number;
  automaticRoutingReady: boolean;
  healthFreshUntil: string | null;
  consecutiveFailures: number;
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProxyBindingListResponse {
  items: ProxyBindingView[];
  total: number;
}

export interface ProxyBindingRequest {
  name: string;
  description?: string;
  providerId: string;
  region?: string;
  expectedExitIp: string;
  credentialRef?: string;
  enabled: boolean;
  expectedVersion?: number;
}

export interface ProxyRoutingCandidateScore {
  bindingProfileId: string;
  providerId: string;
  routingScore: number;
  qualityScore: number;
  reputationScore: number;
  costPerGibUsd: number;
  costScore: number;
  regionScore: number;
  headroomScore: number;
  activeReservations: number;
  maxConcurrentSessions: number;
}

export interface ProxyRoutingDecision {
  sessionId: string;
  bindingProfileId: string;
  providerId: string;
  selectionMode: 'EXPLICIT' | 'AUTO';
  routingScore: number | null;
  qualityScore: number | null;
  reputationScore: number | null;
  costPerGibUsd: number | null;
  activeReservations: number | null;
  maxConcurrentSessions: number | null;
  candidateCount: number;
  candidateScores: ProxyRoutingCandidateScore[];
  selectedAt: string;
}

export type ProxyRebindPhase =
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

export interface ProxyRebindRequest {
  targetBindingProfileId: string;
  reason: string;
}

export interface ProxyRebindOperation {
  workflowId: string;
  operationId: string;
  phase: ProxyRebindPhase;
  createdAt: string;
}

export interface ProxyRebindView {
  workflowId: string;
  sessionId: string;
  sourceBindingProfileId: string | null;
  targetBindingProfileId: string;
  targetBindingVersion: number;
  hibernateOperationId: string | null;
  restoreOperationId: string | null;
  resyncRequestId: string | null;
  phase: ProxyRebindPhase;
  recoveryResult: string | null;
  failureReason: string | null;
  requestedBy: string;
  reason: string;
  requestId: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}
