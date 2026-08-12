/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RemoteDesktopConnection } from '../models/RemoteDesktopConnection.js';
import type { RemoteDesktopParticipant } from '../models/RemoteDesktopParticipant.js';
import type { RemoteDesktopParticipantHistoryPage } from '../models/RemoteDesktopParticipantHistoryPage.js';
import type { RemoteDesktopParticipantList } from '../models/RemoteDesktopParticipantList.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class RemoteDesktopService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Issue a collaborative noVNC ticket without preempting the Agent
     * The ticket is bound to the current Session Context rather than an exclusive HumanTakeover Operation. Connecting keeps an active Agent task alive; Browser Node gives fresh human input priority and resumes deferred Agent input after the human input idle window. Multiple collaborative clients are bounded per Session and use the shared RFB mode. A view-only ticket is enforced by both noVNC and Browser Node; attempted Key, Pointer or Clipboard input is rejected before x11vnc. If the same actor already owns an explicit EXECUTING HumanTakeover, the endpoint preserves that exclusive operation and its release barrier; the Gateway revokes collaborative clients before admitting it.
     * @returns RemoteDesktopConnection Session-bound collaborative connection ticket issued.
     * @throws ApiError
     */
    public createRemoteDesktopConnection({
        sessionId,
        xTenantId,
        xActorId,
        viewOnly = false,
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
        /**
         * Request a server-enforced observation-only connection.
         */
        viewOnly?: boolean,
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
            query: {
                'viewOnly': viewOnly,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List Browser Node-confirmed online remote desktop participants
     * @returns RemoteDesktopParticipantList Current participant projection sourced from real gateway lifecycle events.
     * @throws ApiError
     */
    public listRemoteDesktopParticipants({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RemoteDesktopParticipantList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/desktop-participants',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * List retained terminal remote desktop participant history
     * Returns a stable keyset page of revoked and disconnected connections. Online and revoke-requested participants remain available from the online endpoint and are never removed by terminal-history retention cleanup.
     * @returns RemoteDesktopParticipantHistoryPage Session-bound retained participant history page.
     * @throws ApiError
     */
    public listRemoteDesktopParticipantHistory({
        sessionId,
        xTenantId,
        limit = 20,
        cursor,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
        cursor?: string,
    }): CancelablePromise<RemoteDesktopParticipantHistoryPage> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/desktop-participants/history',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
                'cursor': cursor,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Revoke exactly one remote desktop connection without stopping Agent or Browser
     * @returns RemoteDesktopParticipant Exact connection revocation was durably queued for its Browser Node.
     * @throws ApiError
     */
    public revokeRemoteDesktopParticipant({
        sessionId,
        idempotencyKey,
        connectionId,
        xTenantId,
        xActorId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        connectionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<RemoteDesktopParticipant> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/desktop-participants/{connectionId}:revoke',
            path: {
                'sessionId': sessionId,
                'connectionId': connectionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
}
