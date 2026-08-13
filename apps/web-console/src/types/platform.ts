import type { ResourceStreamConnectionState } from './session';

export interface AuditEventView {
  eventId: string;
  sequenceNo: number;
  sessionId: string | null;
  eventType: string;
  actorType: string;
  actorId: string | null;
  resourceType: string | null;
  resourceId: string | null;
  action: string;
  result: string;
  details: Record<string, unknown>;
  previousEventHash: string | null;
  eventHash: string;
  requestId: string | null;
  retentionUntil: string;
  legalHold: boolean;
  createdAt: string;
}

export interface AuditEventListResponse {
  items: AuditEventView[];
  total: number;
  chainValid: boolean;
  headHash: string | null;
}

export interface AuditEventStreamControl {
  cursor: number;
  resetRequired: boolean;
  connectedAt: string;
}

export interface AuditEventStreamEvent {
  sequence: number;
  occurredAt: string;
  replayed: boolean;
}

export type AuditStreamConnectionState = ResourceStreamConnectionState;

export interface RuntimeBuildView {
  buildId: string;
  engine: string;
  version: string;
  platform: string;
  securityTier: string;
  regressionStatus: string;
  releaseChannel: 'UNRELEASED' | 'CANARY' | 'STABLE' | 'DISABLED';
  signatureVerified: boolean;
  signature: string | null;
  artifactDigest: string | null;
  signingKeyId: string | null;
  sbomUrl: string | null;
  validatedAt: string | null;
  releasedAt: string | null;
  disabledAt: string | null;
  disabledBy: string | null;
  createdAt: string;
}

export interface RuntimeBuildListResponse {
  items: RuntimeBuildView[];
  total: number;
}

export type KeyRotationScope =
  | 'NODE_MTLS'
  | 'RUNTIME_SIGNING'
  | 'PROFILE_KEK'
  | 'REMOTE_DESKTOP'
  | 'AGENT_CAPABILITY';

export type KeyRotationTrigger =
  | 'SCHEDULED'
  | 'PERSONNEL_CHANGE'
  | 'POLICY_CHANGE'
  | 'SUSPECTED_COMPROMISE'
  | 'TENANT_REQUEST';

export interface CreateKeyRotationRequest {
  keyScope: KeyRotationScope;
  oldKeyId: string;
  newKeyId: string;
  rotationTrigger: KeyRotationTrigger;
  reason: string;
  overlapMinutes: number;
}

export interface CompleteKeyRotationRequest {
  newKeyWriteVerified: boolean;
  oldKeyReadVerified: boolean;
  plaintextRejected: boolean;
  affectedWorkloads: number;
  verificationReference: string;
}

export interface KeyRotationRequestView {
  rotationId: string;
  keyScope: KeyRotationScope;
  oldKeyId: string;
  newKeyId: string;
  rotationTrigger: KeyRotationTrigger;
  reason: string;
  requestedOverlapMinutes: number;
  state: 'REQUESTED' | 'ROTATING' | 'COMPLETED' | 'REVOKED' | 'FAILED';
  requestedBy: string;
  approvedBy: string | null;
  completedBy: string | null;
  revokedBy: string | null;
  requestedAt: string;
  approvedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  revokedAt: string | null;
  overlapUntil: string | null;
  progressPercent: number;
  newKeyWriteVerified: boolean | null;
  oldKeyReadVerified: boolean | null;
  plaintextRejected: boolean | null;
  affectedWorkloads: number | null;
  verificationReference: string | null;
  approvalEvidenceHash: string | null;
  completionEvidenceHash: string | null;
}

export interface KeyRotationRequestListResponse {
  items: KeyRotationRequestView[];
  total: number;
}

export type BreakGlassState =
  'REQUESTED' | 'ACTIVE' | 'REJECTED' | 'REVOKED' | 'EXPIRED';

export interface BreakGlassRequestView {
  requestId: string;
  ticketId: string;
  reason: string;
  resourceType: 'SESSION' | 'PROFILE' | 'AUDIT' | 'RUNTIME' | 'TENANT';
  resourceId: string;
  requestedScope:
    | 'READ_SENSITIVE_STATE'
    | 'SECURE_DEBUG'
    | 'AUDIT_EXPORT'
    | 'INCIDENT_RESPONSE';
  state: BreakGlassState;
  requestedBy: string;
  approvedBy: string | null;
  rejectedBy: string | null;
  revokedBy: string | null;
  evidenceHash: string | null;
  requestedAt: string;
  approvedAt: string | null;
  rejectedAt: string | null;
  revokedAt: string | null;
  expiresAt: string;
  reviewedAt: string | null;
}

export interface BreakGlassRequestListResponse {
  items: BreakGlassRequestView[];
  total: number;
}

export interface CreateBreakGlassRequest {
  ticketId: string;
  reason: string;
  resourceType: BreakGlassRequestView['resourceType'];
  resourceId: string;
  requestedScope: BreakGlassRequestView['requestedScope'];
  durationMinutes: number;
}

export interface SecureDebugSessionView {
  debugSessionId: string;
  breakGlassRequestId: string;
  resourceType: 'SESSION';
  resourceId: string;
  operatorId: string;
  state: 'ACTIVE' | 'ENDED' | 'EXPIRED' | 'REVOKED';
  startedAt: string;
  expiresAt: string;
  endedAt: string | null;
  endReason: string | null;
  accessCount: number;
  lastAccessAt: string | null;
  evidenceHeadHash: string | null;
}

export interface SecureDebugSessionListResponse {
  items: SecureDebugSessionView[];
  total: number;
}

export interface SecureDebugSnapshotView {
  debugSessionId: string;
  sessionId: string;
  sessionState: string;
  runtimeBuildId: string | null;
  contextEpoch: number;
  browserGeneration: number;
  networkRevision: number;
  urlOrigin: string | null;
  stateVersion: number;
  targetRevision: number;
  stateQuality: string;
  stateHash: string | null;
  interactiveTargetCount: number;
  sensitiveTargetCount: number;
  capturedAt: string;
  accessCount: number;
  accessEvidenceHash: string;
  dataClassification: 'SENSITIVE_MINIMIZED';
  fieldProjection: string;
}
