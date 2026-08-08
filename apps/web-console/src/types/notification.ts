import type { ResourceStreamConnectionState } from './session';

export type NotificationCategory =
  'SECURITY' | 'RESOURCE' | 'AGENT' | 'RELEASE' | 'SYSTEM';

export type NotificationSeverity = 'INFO' | 'WARNING' | 'CRITICAL';

export interface WorkspaceNotification {
  notificationId: string;
  sequence: number;
  category: NotificationCategory;
  severity: NotificationSeverity;
  title: string;
  body: string;
  eventType: string;
  sessionId: string | null;
  resourceType: string | null;
  resourceId: string | null;
  requestId: string | null;
  route: string;
  read: boolean;
  occurredAt: string;
}

export interface WorkspaceNotificationListResponse {
  items: WorkspaceNotification[];
  unreadCount: number;
  lastReadSequence: number;
  headSequence: number;
  nextBeforeSequence: number | null;
}

export interface WorkspaceNotificationReadState {
  lastReadSequence: number;
  unreadCount: number;
  updatedAt: string;
}

export interface WorkspaceNotificationStreamControl {
  cursor: number;
  resetRequired: boolean;
  connectedAt: string;
}

export interface WorkspaceNotificationStreamEvent {
  sequence: number;
  occurredAt: string;
  replayed: boolean;
}

export type WorkspaceNotificationConnectionState =
  ResourceStreamConnectionState;
