/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RemoteDesktopConnection } from '../models/RemoteDesktopConnection.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class RemoteDesktopService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Issue a collaborative noVNC ticket without preempting the Agent
     * The ticket is bound to the current Session Context rather than an exclusive HumanTakeover Operation. Connecting keeps an active Agent task alive; Browser Node gives fresh human input priority and resumes deferred Agent input after the human input idle window. If the same actor already owns an explicit EXECUTING HumanTakeover, the endpoint preserves that exclusive operation and its release barrier.
     * @returns RemoteDesktopConnection Session-bound collaborative connection ticket issued.
     * @throws ApiError
     */
    public createRemoteDesktopConnection({
        sessionId,
        xTenantId,
        xActorId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<RemoteDesktopConnection> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:desktop-connection',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
}
