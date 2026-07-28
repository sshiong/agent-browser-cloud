import type { SessionState, WorkspaceTagSummary } from './session';

export interface TagSessionView {
  sessionId: string;
  displayName: string;
  state: SessionState;
  region: string;
  updatedAt: string;
}

export interface WorkspaceTagView extends WorkspaceTagSummary {
  description?: string;
  sessions: TagSessionView[];
  sessionCount: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceTagListResponse {
  items: WorkspaceTagView[];
  sessions: TagSessionView[];
  total: number;
}

export interface WorkspaceTagRequest {
  name: string;
  description?: string;
  color: string;
}
