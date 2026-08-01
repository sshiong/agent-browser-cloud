export type WorkspaceBatchAction =
  'START' | 'PAUSE_AGENT' | 'MIGRATE' | 'HIBERNATE';

export type WorkspaceBatchState =
  | 'ACCEPTED'
  | 'EXECUTING'
  | 'CANCELLING'
  | 'SUCCEEDED'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'
  | 'CANCELLED';

export type WorkspaceBatchItemState =
  'ACCEPTED' | 'EXECUTING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface WorkspaceBatchSelector {
  groupId?: string;
  tagIds: string[];
  tagMatch: 'ANY' | 'ALL';
  sessionIds: string[];
}

export interface CreateWorkspaceBatchOperationRequest {
  action: WorkspaceBatchAction;
  selector: WorkspaceBatchSelector;
  reason?: string;
  confirmed: boolean;
}

export interface WorkspaceBatchOperationItem {
  batchItemId: string;
  sessionId: string;
  ordinal: number;
  commandId: string;
  state: WorkspaceBatchItemState;
  childOperationId?: string;
  failureCode?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface WorkspaceBatchOperation {
  batchOperationId: string;
  action: WorkspaceBatchAction;
  state: WorkspaceBatchState;
  selector: WorkspaceBatchSelector;
  reason?: string;
  total: number;
  accepted: number;
  executing: number;
  succeeded: number;
  failed: number;
  cancelled: number;
  cancellationRequested: boolean;
  items: WorkspaceBatchOperationItem[];
  actorId: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceBatchOperationListResponse {
  items: WorkspaceBatchOperation[];
  total: number;
}
