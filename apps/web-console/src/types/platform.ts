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
  signatureVerified: boolean;
  signature: string | null;
  sbomUrl: string | null;
  validatedAt: string | null;
  releasedAt: string | null;
  createdAt: string;
}

export interface RuntimeBuildListResponse {
  items: RuntimeBuildView[];
  total: number;
}
