export interface ProfileView {
  profileId: string;
  tenantId: string;
  name: string;
  description: string | null;
  latestCheckpointId: string | null;
  latestCheckpointEpoch: number | null;
  profileWriteEpoch: number;
  coreSizeBytes: number;
  checkpointFileCount: number;
  restoreStatus: 'EMPTY' | 'TECHNICAL_READY';
  state: 'ACTIVE' | string;
  createdAt: string;
  updatedAt: string;
  lastCheckpointAt: string | null;
}

export interface ProfileListResponse {
  items: ProfileView[];
  total: number;
}

export interface ProfileWarmTierStatus {
  state: 'AWAITING_FIRST_SYNC' | 'LIVE';
  nodeId: string | null;
  profileWriteEpoch: number | null;
  journalSequence: number | null;
  transactionBarrier: string | null;
  changedFileCount: number | null;
  deletedFileCount: number | null;
  reusedChunkCount: number | null;
  uploadedBytes: number | null;
  deferredGroupCount: number | null;
  manifestSha256: string | null;
  committedAt: string | null;
}

export interface CreateProfileRequest {
  profileId: string;
  name: string;
  description?: string;
}

export type ProfileImportState =
  'REQUESTED' | 'UPLOADING' | 'VALIDATING' | 'COMMITTED' | 'FAILED';

export interface ProfileImportView {
  importId: string;
  operationId: string;
  profileId: string;
  profileName: string;
  runtimeBuildId: string;
  archiveSha256: string;
  archiveSizeBytes: number;
  state: ProfileImportState;
  nodeId: string | null;
  checkpointId: string;
  checkpointEpoch: number | null;
  profileWriteEpoch: number | null;
  coreSizeBytes: number | null;
  checkpointFileCount: number | null;
  errorCode: string | null;
  requestId: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface ProfileImportListResponse {
  items: ProfileImportView[];
  total: number;
}

export interface ProfileImportRequest {
  profileId: string;
  profileName: string;
  profileDescription?: string;
  runtimeBuildId: string;
  archiveSha256: string;
  archive: File;
}

export type ProfileExportPurpose =
  | 'INCIDENT_RESPONSE'
  | 'SUPPORT_DIAGNOSTICS'
  | 'COMPLIANCE_EXPORT'
  | 'TENANT_BACKUP';

export interface ProfileExportGrantView {
  grantId: string;
  profileId: string;
  checkpointId: string;
  checkpointEpoch: number;
  purpose: ProfileExportPurpose;
  state: 'ISSUED' | 'REDEEMING' | 'REDEEMED' | 'FAILED';
  expiresAt: string;
  createdAt: string;
  redeemedAt: string | null;
  archiveSha256: string | null;
  archiveSizeBytes: number | null;
  errorCode: string | null;
  requestId: string | null;
}

export interface RedeemProfileExportResponse {
  grantId: string;
  profileId: string;
  checkpointId: string;
  archiveSha256: string;
  archiveSizeBytes: number;
  downloadUrl: string;
  expiresAt: string;
}
