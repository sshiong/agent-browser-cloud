/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentTask } from '../models/AgentTask.js';
import type { OperationResponse } from '../models/OperationResponse.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class HumanTakeoverService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Acquire exclusive HumanTakeover control
     * @returns OperationResponse HumanTakeover input barrier accepted.
     * @throws ApiError
     */
    public requestHumanTakeover({
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
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:takeover',
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
    /**
     * Release HumanTakeover after input cleanup and state resync
     * @returns OperationResponse HumanTakeover release barrier accepted.
     * @throws ApiError
     */
    public releaseHumanTakeover({
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
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:release-takeover',
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
    /**
     * Accept an Agent human-handoff request
     * @returns AgentTask Handoff accepted and HumanTakeover operation created.
     * @throws ApiError
     */
    public acceptAgentHandoff({
        taskId,
        xTenantId,
        xActorId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:accept-handoff',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject an Agent human-handoff request
     * @returns AgentTask Handoff rejected and Agent task failed closed.
     * @throws ApiError
     */
    public rejectAgentHandoff({
        taskId,
        xTenantId,
        xActorId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:reject-handoff',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
}
