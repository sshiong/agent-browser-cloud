/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceNotification } from './WorkspaceNotification.js';
export type WorkspaceNotificationListResponse = {
    items: Array<WorkspaceNotification>;
    unreadCount: number;
    lastReadSequence: number;
    headSequence: number;
    nextBeforeSequence: number | null;
};
