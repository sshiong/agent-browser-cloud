/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceOverview } from '../models/WorkspaceOverview.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class WorkspaceOverviewService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Get the authenticated workspace's authoritative operational overview
     * Returns one tenant-scoped PostgreSQL snapshot covering Sessions, active Operations, Browser Node capacity, Proxy bindings, Agent tasks, current resource cost and recent security signals. Browser Node health is a global infrastructure projection visible only to Platform Admins; all tenant-owned counts remain tenant isolated.
     *
     * @returns WorkspaceOverview Current authoritative Workspace overview and stream cursor.
     * @throws ApiError
     */
    public getWorkspaceOverview(): CancelablePromise<WorkspaceOverview> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/workspace-overview',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Stream resumable invalidations for the authenticated Workspace overview
     * Emits payload-free change notifications. Clients resume with Last-Event-ID and refetch the authoritative overview after each event; telemetry samples alone do not invalidate the stream.
     *
     * @returns string Resumable Workspace overview Server-Sent Event stream.
     * @throws ApiError
     */
    public streamWorkspaceOverviewChanges({
        lastEventId,
    }: {
        lastEventId?: number,
    }): CancelablePromise<string> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/workspace-overview/event-stream',
            headers: {
                'Last-Event-ID': lastEventId,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                429: `The bounded concurrent stream capacity has been reached.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
}
