/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CommitEnvironmentImportRequest } from '../models/CommitEnvironmentImportRequest.js';
import type { EnvironmentImport } from '../models/EnvironmentImport.js';
import type { EnvironmentImportListResponse } from '../models/EnvironmentImportListResponse.js';
import type { PreviewEnvironmentImportRequest } from '../models/PreviewEnvironmentImportRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class EnvironmentImportService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List the current operator's tenant-authoritative import jobs
     * @returns EnvironmentImportListResponse The latest import jobs owned by the authenticated actor.
     * @throws ApiError
     */
    public listEnvironmentImports(): CancelablePromise<EnvironmentImportListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/environment-imports',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Validate an Environment manifest without creating runtime resources
     * @returns EnvironmentImport PostgreSQL-authoritative validation result.
     * @throws ApiError
     */
    public previewEnvironmentImport({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: PreviewEnvironmentImportRequest,
    }): CancelablePromise<EnvironmentImport> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/environment-imports:preview',
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
     * Read one actor-owned Environment Import ledger
     * @returns EnvironmentImport Import validation and execution ledger.
     * @throws ApiError
     */
    public getEnvironmentImport({
        importId,
    }: {
        importId: string,
    }): CancelablePromise<EnvironmentImport> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/environment-imports/{importId}',
            path: {
                'importId': importId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Create every validated Session and Operation in one transaction
     * A failed item rolls back the entire import; partial success is never published.
     * @returns EnvironmentImport Committed import with real Session and Operation identifiers.
     * @throws ApiError
     */
    public commitEnvironmentImport({
        importId,
        idempotencyKey,
        requestBody,
    }: {
        importId: string,
        idempotencyKey: string,
        requestBody: CommitEnvironmentImportRequest,
    }): CancelablePromise<EnvironmentImport> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/environment-imports/{importId}:commit',
            path: {
                'importId': importId,
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
