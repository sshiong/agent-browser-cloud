import type {
  WorkspaceBatchItemState,
  WorkspaceBatchState,
} from './workspaceBatch';

export type WorkspaceMetadataBatchAction =
  'ASSIGN_GROUP' | 'REMOVE_GROUP' | 'ASSIGN_TAGS' | 'REMOVE_TAGS';

export interface WorkspaceMetadataBatchSelector {
  groupId?: string;
  tagIds: string[];
  tagMatch: 'ANY' | 'ALL';
  sessionIds: string[];
}

export interface WorkspaceMetadataBatchTarget {
  groupId?: string;
  tagIds: string[];
}

export interface CreateWorkspaceMetadataBatchOperationRequest {
  action: WorkspaceMetadataBatchAction;
  selector: WorkspaceMetadataBatchSelector;
  target: WorkspaceMetadataBatchTarget;
  reason: string;
  confirmed: boolean;
}

export interface WorkspaceMetadataBatchOperationItem {
  batchItemId: string;
  sessionId: string;
  ordinal: number;
  state: WorkspaceBatchItemState;
  failureCode?: string;
  attempt: number;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface WorkspaceMetadataBatchOperation {
  batchOperationId: string;
  action: WorkspaceMetadataBatchAction;
  state: WorkspaceBatchState;
  selector: WorkspaceMetadataBatchSelector;
  target: WorkspaceMetadataBatchTarget;
  reason: string;
  total: number;
  accepted: number;
  executing: number;
  succeeded: number;
  failed: number;
  cancelled: number;
  cancellationRequested: boolean;
  items: WorkspaceMetadataBatchOperationItem[];
  actorId: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceMetadataBatchOperationListResponse {
  items: WorkspaceMetadataBatchOperation[];
  total: number;
}
