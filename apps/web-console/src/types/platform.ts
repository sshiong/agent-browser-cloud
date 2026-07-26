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
