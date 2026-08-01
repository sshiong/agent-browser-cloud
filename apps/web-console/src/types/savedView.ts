import type { SessionState } from './session';

export type EnvironmentSavedViewScope = 'PERSONAL' | 'WORKSPACE';
export type EnvironmentPrimaryView = 'ALL' | 'RUNNING' | 'STOPPED' | 'ABNORMAL';
export type EnvironmentSavedViewTagMatch = 'ANY' | 'ALL';

export interface EnvironmentSavedViewConfiguration {
  primaryView: EnvironmentPrimaryView;
  sessionState?: SessionState | null;
  searchQuery: string;
  groupId?: string | null;
  tagIds: string[];
  tagMatch: EnvironmentSavedViewTagMatch;
  showRuntimeColumn: boolean;
  showContextColumn: boolean;
  showOperationColumn: boolean;
}

export interface CreateEnvironmentSavedViewRequest extends EnvironmentSavedViewConfiguration {
  name: string;
  scope: EnvironmentSavedViewScope;
}

export interface UpdateEnvironmentSavedViewRequest extends EnvironmentSavedViewConfiguration {
  expectedVersion: number;
  name: string;
}

export interface EnvironmentSavedView extends EnvironmentSavedViewConfiguration {
  savedViewId: string;
  name: string;
  scope: EnvironmentSavedViewScope;
  ownerActorId: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface EnvironmentSavedViewListResponse {
  items: EnvironmentSavedView[];
  total: number;
}
