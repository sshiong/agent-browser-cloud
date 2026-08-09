export interface RuntimeValidationView {
  validationId: string;
  buildId: string;
  suiteVersion: string;
  environmentDigest: string;
  replayDatasetId: string;
  persona: string;
  state: 'RUNNING' | 'PASSED' | 'DEGRADED' | 'FAILED';
  requiredTests: number;
  requiredFailures: number;
  optionalTests: number;
  optionalFailures: number;
  declaredCapabilities: Record<string, boolean>;
  observedCapabilities: Record<string, boolean>;
  optionalFailureCodes: string[];
  evidenceHash: string | null;
  requestedBy: string;
  startedAt: string;
  completedAt: string | null;
  job?: RuntimeValidationJobView | null;
}

export interface RuntimeValidationJobView {
  validationId: string;
  browserEngine: string;
  browserVersion: string;
  operatingSystem: string;
  architecture: string;
  requiredWorkerCapabilities: Record<string, boolean>;
  state: 'QUEUED' | 'CLAIMED' | 'EXECUTING' | 'ACKED' | 'COMMITTED' | 'FAILED';
  attempt: number;
  maximumAttempts: number;
  workerId: string | null;
  claimEpoch: number;
  availableAt: string;
  leaseExpiresAt: string | null;
  lastHeartbeatAt: string | null;
  failureCode: string | null;
  resultHash: string | null;
  updatedAt: string;
}

export interface CostRateView {
  pricingVersion: string;
  region: string;
  resourceTemplate: string;
  baseHourlyUsd: number;
  cpuCoreHourlyUsd: number;
  memoryGibHourlyUsd: number;
  desktopHourlyUsd: number;
  gpuHourlyUsd: number;
  mediaHourlyUsd: number;
  effectiveAt: string;
  createdBy: string;
  createdAt: string;
}

export interface ErrorBudgetView {
  tenantId: string;
  availabilityTarget: number;
  latencyP95TargetMs: number;
  windowMinutes: number;
  allowedUnavailableSeconds: number;
  consumedUnavailableSeconds: number;
  remainingUnavailableSeconds: number;
  burnRatio: number;
  state: 'HEALTHY' | 'EXHAUSTED';
  windowStartedAt: string;
  calculatedAt: string;
}

export interface ReleaseFreezeView {
  tenantId: string;
  enabled: boolean;
  phase: 'OPEN' | 'FROZEN' | 'RECOVERING';
  frozen: boolean;
  currentBurnRate: number;
  freezeBurnRateThreshold: number;
  recoveryBurnRateThreshold: number;
  evaluationWindowMinutes: number;
  recoveryStableMinutes: number;
  reasonCode: string;
  stableSince: string | null;
  frozenAt: string | null;
  clearedAt: string | null;
  evaluatedAt: string;
  version: number;
}

export interface MediaQuotaView {
  tenantId: string;
  maxConcurrentStreams: number;
  maxBitrateKbps: number;
  activeStreams: number;
  activeBitrateKbps: number;
  updatedBy: string;
  updatedAt: string;
}

export interface RetentionPolicyView {
  tenantId: string;
  dataClass: string;
  retentionDays: number;
  legalHold: boolean;
  residencyRegion: string;
  updatedBy: string;
  updatedAt: string;
}

export interface SlaExclusionView {
  tenantId: string;
  exclusionCode: string;
  description: string;
  enabled: boolean;
  updatedBy: string;
  updatedAt: string;
}

export interface LicenseInventoryView {
  componentId: string;
  componentType: 'RUNTIME' | 'EXTENSION' | 'SERVICE' | 'SDK';
  componentName: string;
  componentVersion: string;
  licenseId: string;
  sourceUrl: string;
  approved: boolean;
  evidenceHash: string;
  updatedBy: string;
  updatedAt: string;
}

export interface RegionView {
  regionId: string;
  role: 'PRIMARY' | 'SECONDARY' | 'DR';
  admissionState: 'OPEN' | 'CLOSED' | 'FAILOVER_READY';
  replicationLagSeconds: number;
  lastVerifiedAt: string;
  updatedBy: string;
}

export interface RecoveryGameDayView {
  gameDayId: string;
  scenario: string;
  sourceRegion: string;
  targetRegion: string;
  state: 'QUEUED' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ABORTED';
  rtoTargetSeconds: number;
  rpoTargetSeconds: number;
  observedRtoSeconds: number | null;
  observedRpoSeconds: number | null;
  dataLossRecords: number | null;
  evidenceHash: string | null;
  startedBy: string;
  startedAt: string;
  completedAt: string | null;
  executionMode: 'MANUAL' | 'AUTO';
  environment: 'TEST' | 'STAGING' | 'PRODUCTION';
  blastRadius: {
    scope: 'TEST_FIXTURE' | 'TENANT' | 'NAMESPACE' | 'REGION';
    maximumTargets: number;
    targetIds: string[];
  } | null;
  maximumDurationSeconds: number;
  approvalRequestId: string | null;
  currentStage: string;
  abortRequested: boolean;
  recoveryConfirmed: boolean | null;
  failureCode: string | null;
  job: RecoveryGameDayJobView | null;
}

export interface RecoveryGameDayJobView {
  gameDayId: string;
  scenarioCode: string;
  environment: 'TEST' | 'STAGING' | 'PRODUCTION';
  requiredWorkerCapabilities: Record<string, boolean>;
  state:
    | 'QUEUED'
    | 'CLAIMED'
    | 'EXECUTING'
    | 'RECOVERY_REQUIRED'
    | 'RECOVERING'
    | 'ACKED'
    | 'COMMITTED'
    | 'FAILED'
    | 'ABORTED';
  currentStage: string;
  attempt: number;
  maximumAttempts: number;
  recoveryAttempt: number;
  maximumRecoveryAttempts: number;
  workerId: string | null;
  claimEpoch: number;
  availableAt: string;
  leaseExpiresAt: string | null;
  lastHeartbeatAt: string | null;
  abortDeadline: string;
  abortRequested: boolean;
  faultInjected: boolean;
  recoveryConfirmed: boolean | null;
  failureCode: string | null;
  resultHash: string | null;
  updatedAt: string;
}

export interface RecoveryGameDayEventView {
  eventId: string;
  gameDayId: string;
  eventType: string;
  fromState: string | null;
  toState: string;
  stage: string;
  workerId: string | null;
  claimEpoch: number;
  attempt: number;
  reasonCode: string | null;
  occurredAt: string;
}

export interface RecoveryGameDayEventPage {
  items: RecoveryGameDayEventView[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface RecoveryGameDayTrendView {
  scenario: string;
  environment: 'TEST' | 'STAGING' | 'PRODUCTION';
  totalRuns: number;
  passedRuns: number;
  failedRuns: number;
  abortedRuns: number;
  recoveryUnknownRuns: number;
  passRatePercent: number;
  p95RtoSeconds: number | null;
  p95RpoSeconds: number | null;
  openTicketCount: number;
  latestRunAt: string;
}

export interface RecoveryGameDayReportExportView {
  exportId: string;
  gameDayId: string;
  reportFormat: 'JSON';
  eventCount: number;
  report: Record<string, unknown>;
  reportHash: string;
  signatureAlgorithm: 'HMAC-SHA256';
  signingKeyId: string;
  signature: string;
  generatedBy: string;
  generatedAt: string;
}

export interface RecoveryGameDayRemediationView {
  ticketId: string;
  gameDayId: string;
  scenario: string;
  environment: 'TEST' | 'STAGING' | 'PRODUCTION';
  severity: 'P1' | 'P2' | 'P3';
  state: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
  reasonCode: string;
  summary: string;
  ownerId: string | null;
  resolution: string | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
  resolvedAt: string | null;
}

export interface ComplianceSnapshotView {
  snapshotId: string;
  tenantId: string;
  framework: string;
  controlCount: number;
  passingControls: number;
  evidenceHash: string;
  evidence: Record<string, boolean>;
  generatedBy: string;
  generatedAt: string;
}

export interface EnterpriseOverviewResponse {
  validations: RuntimeValidationView[];
  costRates: CostRateView[];
  mediaQuota: MediaQuotaView | null;
  errorBudget: ErrorBudgetView | null;
  releaseFreeze?: ReleaseFreezeView | null;
  slaExclusions: SlaExclusionView[];
  retentionPolicies: RetentionPolicyView[];
  licenseInventory: LicenseInventoryView[];
  regions: RegionView[];
  recoveryGameDays: RecoveryGameDayView[];
  recoveryGameDayTrends: RecoveryGameDayTrendView[];
  recoveryGameDayRemediations: RecoveryGameDayRemediationView[];
  latestCompliance: ComplianceSnapshotView | null;
  generatedAt: string;
}
