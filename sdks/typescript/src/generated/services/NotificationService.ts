/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { UpdateNotificationReadCursorRequest } from '../models/UpdateNotificationReadCursorRequest.js';
import type { WorkspaceNotificationListResponse } from '../models/WorkspaceNotificationListResponse.js';
import type { WorkspaceNotificationReadState } from '../models/WorkspaceNotificationReadState.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class NotificationService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List the authenticated actor's workspace notifications
     * Returns a bounded tenant-scoped projection of high-signal immutable audit events. Read state is held in PostgreSQL per authenticated actor and is shared by Web and Tauri clients. The feed starts at feature deployment and retains entries for 90 days.
     *
     * @returns WorkspaceNotificationListResponse Current notification page and durable actor read state.
     * @throws ApiError
     */
    public listWorkspaceNotifications({
        limit = 30,
        beforeSequence,
    }: {
        limit?: number,
        /**
         * Exclusive audit sequence cursor for older entries.
         */
        beforeSequence?: number,
    }): CancelablePromise<WorkspaceNotificationListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/notifications',
            query: {
                'limit': limit,
                'beforeSequence': beforeSequence,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Monotonically advance the authenticated actor's notification read cursor
     * The requested sequence must not exceed the current tenant notification head. Repeated or older cursor updates are idempotent and never make notifications unread.
     *
     * @returns WorkspaceNotificationReadState Committed read cursor and remaining unread count.
     * @throws ApiError
     */
    public updateWorkspaceNotificationReadCursor({
        requestBody,
    }: {
        requestBody: UpdateNotificationReadCursorRequest,
    }): CancelablePromise<WorkspaceNotificationReadState> {
        return this.httpRequest.request({
            method: 'PATCH',
            url: '/api/v1/notifications/read-cursor',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
}
