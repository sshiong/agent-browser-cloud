/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { NotificationCategory } from './NotificationCategory.js';
import type { NotificationSeverity } from './NotificationSeverity.js';
export type WorkspaceNotification = {
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
};
