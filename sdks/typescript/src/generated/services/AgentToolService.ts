/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentTask } from '../models/AgentTask.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class AgentToolService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Execute and verify a supported Agent plan
     * Executes read, Navigate, Click, Type, Scroll, Wait, and human-handoff steps with durable checkpoints.
     * @returns AgentTask Completed, failed, or previously completed Agent task.
     * @throws ApiError
     */
    public executeAgentTask({
        taskId,
        idempotencyKey,
        xTenantId,
    }: {
        taskId: string,
        idempotencyKey: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:execute',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
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
