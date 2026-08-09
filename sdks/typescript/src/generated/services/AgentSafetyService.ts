/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentExecutionJob } from '../models/AgentExecutionJob.js';
import type { AgentExecutionJobClaim } from '../models/AgentExecutionJobClaim.js';
import type { AgentExecutionJobClaimRequest } from '../models/AgentExecutionJobClaimRequest.js';
import type { AgentTask } from '../models/AgentTask.js';
import type { AgentTaskListResponse } from '../models/AgentTaskListResponse.js';
import type { AgentTaskSummaryListResponse } from '../models/AgentTaskSummaryListResponse.js';
import type { ClaimAgentExecutionJobRequest } from '../models/ClaimAgentExecutionJobRequest.js';
import type { CreateAgentTaskRequest } from '../models/CreateAgentTaskRequest.js';
import type { FailAgentExecutionJobRequest } from '../models/FailAgentExecutionJobRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class AgentSafetyService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Validate and persist a bounded Agent plan
     * External context is data-only. Structured actions bind to the current Target Revision and sensitive target policy.
     * @returns AgentTask A planned or explicitly blocked Agent task.
     * @throws ApiError
     */
    public createAgentTask({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateAgentTaskRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-tasks',
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
            },
        });
    }
    /**
     * List tenant Agent tasks and plan-security decisions
     * @returns AgentTaskListResponse Tenant-scoped Agent task list.
     * @throws ApiError
     */
    public listAgentTasks({
        xTenantId,
        limit = 20,
        offset,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
        offset?: number,
    }): CancelablePromise<AgentTaskListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-tasks',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
                'offset': offset,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * List lightweight tenant Agent task summaries with stable keyset pagination
     * Returns scalar task state and database-computed counts without transferring plan, execution-result, allowed-domain, or security-event JSON payloads.
     * @returns AgentTaskSummaryListResponse Bounded tenant-scoped Agent task summary page and authoritative aggregate metrics.
     * @throws ApiError
     */
    public listAgentTaskSummaries({
        xTenantId,
        limit = 20,
        cursor,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
        /**
         * Opaque cursor returned by the previous response.
         */
        cursor?: string,
    }): CancelablePromise<AgentTaskSummaryListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-task-summaries',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
                'cursor': cursor,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Get an Agent task and its redacted security evidence
     * @returns AgentTask Tenant-scoped Agent task.
     * @throws ApiError
     */
    public getAgentTask({
        taskId,
        xTenantId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-tasks/{taskId}',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Claim one opaque Agent execution job with a fenced lease
     * Requires the dedicated AGENT_WORKER role. No prompt, plan, page data, capability token, or customer credential crosses this boundary.
     * @returns AgentExecutionJobClaim Opaque execution job claimed; the single-use Claim Token is returned only once.
     * @throws ApiError
     */
    public claimAgentExecutionJob({
        requestBody,
    }: {
        requestBody: ClaimAgentExecutionJobRequest,
    }): CancelablePromise<AgentExecutionJobClaim> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs:claim',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * ACK execution start for a claimed Agent job
     * @returns AgentExecutionJob Job entered EXECUTING.
     * @throws ApiError
     */
    public startAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentExecutionJobClaimRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:start',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Renew an Agent Worker fenced lease
     * @returns AgentExecutionJob Lease renewed.
     * @throws ApiError
     */
    public heartbeatAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentExecutionJobClaimRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:heartbeat',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Ask the Control Plane safety kernel to drive an opaque Agent job
     * The Worker cannot invoke Browser Node tools or modify cgroups directly. Capability, Operation, Outbox, revision and human-governance enforcement remain in the Control Plane.
     * @returns AgentExecutionJob Job committed or waiting on an authoritative asynchronous task state.
     * @throws ApiError
     */
    public driveAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentExecutionJobClaimRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:drive',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject, retry, or permanently fail a claimed Agent job
     * @returns AgentExecutionJob Failure durably projected to the queue and task.
     * @throws ApiError
     */
    public failAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: FailAgentExecutionJobRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:fail',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Approve a pending high-risk Agent plan
     * @returns AgentTask Approved task, now eligible for execution.
     * @throws ApiError
     */
    public approveAgentTask({
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
            url: '/api/v1/agent-tasks/{taskId}:approve',
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
     * Reject a pending high-risk Agent plan
     * @returns AgentTask Rejected task.
     * @throws ApiError
     */
    public rejectAgentTask({
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
            url: '/api/v1/agent-tasks/{taskId}:reject',
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
