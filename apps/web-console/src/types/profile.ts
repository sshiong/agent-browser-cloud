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
