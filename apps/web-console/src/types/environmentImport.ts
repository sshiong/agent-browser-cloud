import type { AgentPolicy, ResourcePolicyRequest } from './session';

export type EnvironmentImportState =
  'VALIDATED' | 'INVALID' | 'EXECUTING' | 'COMMITTED';
export type EnvironmentImportValidationState = 'READY' | 'INVALID';
export type EnvironmentImportExecutionState = 'PENDING' | 'SUCCEEDED';

export interface EnvironmentImportSpec {
  displayName: string;
  description?: string;
  profileId: string;
  runtimeBuildId?: string;
  applicationId?: string;
  groupId?: string;
  tagIds?: string[];
  region?: string;
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
}

export interface PreviewEnvironmentImportRequest {
  schemaVersion: 1;
  name: string;
  environments: EnvironmentImportSpec[];
}

export interface EnvironmentImportItem {
  itemId: string;
  itemIndex: number;
  specification: EnvironmentImportSpec;
  validationState: EnvironmentImportValidationState;
  validationErrors: string[];
  executionState: EnvironmentImportExecutionState;
  sessionId?: string;
  operationId?: string;
  requestId?: string;
  updatedAt: string;
}

export interface EnvironmentImport {
  importId: string;
  name: string;
  schemaVersion: number;
  manifestHash: string;
  state: EnvironmentImportState;
  totalCount: number;
  readyCount: number;
  succeededCount: number;
  items: EnvironmentImportItem[];
  createdAt: string;
  updatedAt: string;
  committedAt?: string;
  version: number;
}

export interface EnvironmentImportListItem {
  importId: string;
  name: string;
  state: EnvironmentImportState;
  totalCount: number;
  readyCount: number;
  succeededCount: number;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface EnvironmentImportListResponse {
  items: EnvironmentImportListItem[];
  total: number;
}
