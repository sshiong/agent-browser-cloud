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
import type { WorkspaceTag } from '../models/WorkspaceTag.js';
import type { WorkspaceTagListResponse } from '../models/WorkspaceTagListResponse.js';
import type { WorkspaceTagRequest } from '../models/WorkspaceTagRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class TagsService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List tenant Workspace Tags and authoritative Session assignments
     * @returns WorkspaceTagListResponse Tags and tenant Sessions.
     * @throws ApiError
     */
    public listWorkspaceTags(): CancelablePromise<WorkspaceTagListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/tags',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Create an audited reusable Workspace Tag
     * @returns WorkspaceTag Tag created.
     * @throws ApiError
     */
    public createWorkspaceTag({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: WorkspaceTagRequest,
    }): CancelablePromise<WorkspaceTag> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/tags',
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
     * Update Tag metadata
     * @returns WorkspaceTag Tag updated.
     * @throws ApiError
     */
    public updateWorkspaceTag({
        tagId,
        idempotencyKey,
        requestBody,
    }: {
        tagId: string,
        idempotencyKey: string,
        requestBody: WorkspaceTagRequest,
    }): CancelablePromise<WorkspaceTag> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/tags/{tagId}',
            path: {
                'tagId': tagId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Delete a Tag without terminating its Sessions
     * @returns void
     * @throws ApiError
     */
    public deleteWorkspaceTag({
        tagId,
        idempotencyKey,
    }: {
        tagId: string,
        idempotencyKey: string,
    }): CancelablePromise<void> {
        return this.httpRequest.request({
            method: 'DELETE',
            url: '/api/v1/tags/{tagId}',
            path: {
                'tagId': tagId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Assign a tenant Session to the Tag
     * @returns WorkspaceTag Updated Tag.
     * @throws ApiError
     */
    public assignSessionToWorkspaceTag({
        tagId,
        sessionId,
        idempotencyKey,
    }: {
        tagId: string,
        sessionId: string,
        idempotencyKey: string,
    }): CancelablePromise<WorkspaceTag> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/tags/{tagId}/sessions/{sessionId}',
            path: {
                'tagId': tagId,
                'sessionId': sessionId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Remove a Tag assignment from the Session
     * @returns WorkspaceTag Updated Tag.
     * @throws ApiError
     */
    public unassignSessionFromWorkspaceTag({
        tagId,
        sessionId,
        idempotencyKey,
    }: {
        tagId: string,
        sessionId: string,
        idempotencyKey: string,
    }): CancelablePromise<WorkspaceTag> {
        return this.httpRequest.request({
            method: 'DELETE',
            url: '/api/v1/tags/{tagId}/sessions/{sessionId}',
            path: {
                'tagId': tagId,
                'sessionId': sessionId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
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
