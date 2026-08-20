/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserFindRequest } from '../models/AgentBrowserFindRequest.js';
import type { AgentBrowserInspectRequest } from '../models/AgentBrowserInspectRequest.js';
import type { AgentBrowserSnapshot } from '../models/AgentBrowserSnapshot.js';
import type { AgentBrowserTargetList } from '../models/AgentBrowserTargetList.js';
import type { AgentTask } from '../models/AgentTask.js';
import type { BrowserState } from '../models/BrowserState.js';
import type { ExecuteAgentBrowserActionsRequest } from '../models/ExecuteAgentBrowserActionsRequest.js';
import type { StateResyncRequest } from '../models/StateResyncRequest.js';
import type { StateResyncResponse } from '../models/StateResyncResponse.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class StateService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Get the latest Browser Current State
     * @returns BrowserState Latest tenant-scoped Browser State.
     * @throws ApiError
     */
    public getBrowserState({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<BrowserState> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/state',
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
     * Get one PostgreSQL-authoritative structured page snapshot
     * Returns DOM/accessibility-derived interactive state. Ordinary pages do not require OCR or screenshots.
     * @returns AgentBrowserSnapshot Structured snapshot and cursor for subsequent inspect or action calls.
     * @throws ApiError
     */
    public getAgentBrowserSnapshot({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserSnapshot> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/snapshot',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Inspect structured elements without another Browser Node round trip
     * @returns AgentBrowserTargetList Requested elements from the exact state cursor.
     * @throws ApiError
     */
    public inspectAgentBrowserElements({
        sessionId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        requestBody: AgentBrowserInspectRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserTargetList> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/inspect',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Find structured elements by semantic name, role, type, stable ID, or visibility reason
     * @returns AgentBrowserTargetList Bounded semantic matches from the authoritative current state.
     * @throws ApiError
     */
    public findAgentBrowserElements({
        sessionId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        requestBody: AgentBrowserFindRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserTargetList> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/find',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Execute one ordered state-fenced Browser action batch
     * Provides the browser.execute_actions fast path. The gateway validates one authoritative state cursor, persists one auditable Agent Task, executes primitives in order, checks state between actions, honors stopOnError, and yields to real VNC input without forcing takeover.
     *
     * @returns AgentTask Persisted task after immediate execution or durable enqueue.
     * @throws ApiError
     */
    public executeAgentBrowserActions({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: ExecuteAgentBrowserActionsRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/execute-actions',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Request a Full or Region Browser State Resync
     * @returns StateResyncResponse State Resync command was queued.
     * @throws ApiError
     */
    public resyncBrowserState({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: StateResyncRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<StateResyncResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:resync-state',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
                429: `The bounded concurrent stream capacity has been reached.`,
            },
        });
    }
}
