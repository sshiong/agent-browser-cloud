import type { MaximumReachedPolicy, SessionState } from './session';

export interface GroupSessionView {
  sessionId: string;
  displayName: string;
  state: SessionState;
  region: string;
  updatedAt: string;
}

export interface WorkspaceGroupView {
  groupId: string;
  name: string;
  description?: string;
  color: string;
  defaultOnMaximumReached: MaximumReachedPolicy;
  defaultAllowMigration: boolean;
  defaultAllowHibernate: boolean;
  sessions: GroupSessionView[];
  sessionCount: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceGroupListResponse {
  items: WorkspaceGroupView[];
  unassignedSessions: GroupSessionView[];
  total: number;
}

export interface WorkspaceGroupRequest {
  name: string;
  description?: string;
  color: string;
  defaultOnMaximumReached: MaximumReachedPolicy;
  defaultAllowMigration: boolean;
  defaultAllowHibernate: boolean;
}
