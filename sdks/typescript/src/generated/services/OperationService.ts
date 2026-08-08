/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CancelWorkspaceBatchOperationRequest } from '../models/CancelWorkspaceBatchOperationRequest.js';
import type { CreateWorkspaceBatchOperationRequest } from '../models/CreateWorkspaceBatchOperationRequest.js';
import type { CreateWorkspaceMetadataBatchOperationRequest } from '../models/CreateWorkspaceMetadataBatchOperationRequest.js';
import type { WorkspaceBatchOperation } from '../models/WorkspaceBatchOperation.js';
import type { WorkspaceBatchOperationListResponse } from '../models/WorkspaceBatchOperationListResponse.js';
import type { WorkspaceMetadataBatchOperation } from '../models/WorkspaceMetadataBatchOperation.js';
import type { WorkspaceMetadataBatchOperationListResponse } from '../models/WorkspaceMetadataBatchOperationListResponse.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class OperationService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List recent tenant Workspace lifecycle batches
     * @returns WorkspaceBatchOperationListResponse Real routed-command and child-operation aggregate state.
     * @throws ApiError
     */
    public listWorkspaceBatchOperations({
        limit = 20,
    }: {
        limit?: number,
    }): CancelablePromise<WorkspaceBatchOperationListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/workspace-batch-operations',
            query: {
                'limit': limit,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Submit a bounded Group, Tag, or explicit Session lifecycle batch
     * Creates one durable routed Coordinator command per selected Session. Risky actions require an explicit reason and confirmation. The API never mutates browser nodes directly.
     *
     * @returns WorkspaceBatchOperation Batch accepted and durably routed.
     * @throws ApiError
     */
    public createWorkspaceBatchOperation({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: CreateWorkspaceBatchOperationRequest,
    }): CancelablePromise<WorkspaceBatchOperation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/workspace-batch-operations',
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Read authoritative batch progress
     * @returns WorkspaceBatchOperation Current aggregate and per-Session state.
     * @throws ApiError
     */
    public getWorkspaceBatchOperation({
        batchOperationId,
    }: {
        batchOperationId: string,
    }): CancelablePromise<WorkspaceBatchOperation> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/workspace-batch-operations/{batchOperationId}',
            path: {
                'batchOperationId': batchOperationId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Cancel only batch items that have not started
     * Executing child Operations continue and remain visible in aggregate progress.
     * @returns WorkspaceBatchOperation Cancellation request recorded and pending items cancelled.
     * @throws ApiError
     */
    public cancelWorkspaceBatchOperation({
        batchOperationId,
        idempotencyKey,
        requestBody,
    }: {
        batchOperationId: string,
        idempotencyKey: string,
        requestBody: CancelWorkspaceBatchOperationRequest,
    }): CancelablePromise<WorkspaceBatchOperation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/workspace-batch-operations/{batchOperationId}:cancel',
            path: {
                'batchOperationId': batchOperationId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * List recent tenant Group/Tag membership batches
     * @returns WorkspaceMetadataBatchOperationListResponse Authoritative PostgreSQL aggregate and per-Session execution state.
     * @throws ApiError
     */
    public listWorkspaceMetadataBatchOperations({
        limit = 20,
    }: {
        limit?: number,
    }): CancelablePromise<WorkspaceMetadataBatchOperationListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/workspace-metadata-batch-operations',
            query: {
                'limit': limit,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Submit a bounded Group or Tag membership mutation batch
     * Resolves no more than 100 tenant Sessions, persists one crash-recoverable item per Session, and executes each assignment with a PostgreSQL lease. A reason and explicit confirmation are mandatory. The request never mutates browser nodes.
     *
     * @returns WorkspaceMetadataBatchOperation Metadata batch accepted into the durable worker ledger.
     * @throws ApiError
     */
    public createWorkspaceMetadataBatchOperation({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: CreateWorkspaceMetadataBatchOperationRequest,
    }): CancelablePromise<WorkspaceMetadataBatchOperation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/workspace-metadata-batch-operations',
            headers: {
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
     * Read authoritative Group/Tag membership batch progress
     * @returns WorkspaceMetadataBatchOperation Current aggregate and per-Session mutation state.
     * @throws ApiError
     */
    public getWorkspaceMetadataBatchOperation({
        batchOperationId,
    }: {
        batchOperationId: string,
    }): CancelablePromise<WorkspaceMetadataBatchOperation> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/workspace-metadata-batch-operations/{batchOperationId}',
            path: {
                'batchOperationId': batchOperationId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Cancel Group/Tag mutation items that have not started
     * Executing items finish atomically; only ACCEPTED items become CANCELLED.
     * @returns WorkspaceMetadataBatchOperation Cancellation recorded and pending items cancelled.
     * @throws ApiError
     */
    public cancelWorkspaceMetadataBatchOperation({
        batchOperationId,
        idempotencyKey,
        requestBody,
    }: {
        batchOperationId: string,
        idempotencyKey: string,
        requestBody: CancelWorkspaceBatchOperationRequest,
    }): CancelablePromise<WorkspaceMetadataBatchOperation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/workspace-metadata-batch-operations/{batchOperationId}:cancel',
            path: {
                'batchOperationId': batchOperationId,
            },
            headers: {
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
}
