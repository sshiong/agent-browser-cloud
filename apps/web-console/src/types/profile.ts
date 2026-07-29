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
