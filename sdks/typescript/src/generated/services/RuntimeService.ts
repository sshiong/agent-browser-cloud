/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CompleteRuntimeValidationRequest } from '../models/CompleteRuntimeValidationRequest.js';
import type { CreateRuntimeDisableRequest } from '../models/CreateRuntimeDisableRequest.js';
import type { CreateRuntimeReleaseRequest } from '../models/CreateRuntimeReleaseRequest.js';
import type { RuntimeBuildListResponse } from '../models/RuntimeBuildListResponse.js';
import type { RuntimeReleaseRequest } from '../models/RuntimeReleaseRequest.js';
import type { RuntimeReleaseRequestListResponse } from '../models/RuntimeReleaseRequestListResponse.js';
import type { RuntimeValidation } from '../models/RuntimeValidation.js';
import type { StartRuntimeValidationRequest } from '../models/StartRuntimeValidationRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class RuntimeService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List Runtime Build Registry entries
     * @returns RuntimeBuildListResponse Runtime validation and supply-chain admission evidence.
     * @throws ApiError
     */
    public listRuntimeBuilds({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RuntimeBuildListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/runtime-builds',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Request a dual-control Runtime promotion
     * @returns RuntimeReleaseRequest Promotion request awaiting a second Platform Admin.
     * @throws ApiError
     */
    public requestRuntimePromotion({
        buildId,
        requestBody,
        xTenantId,
        xActorId,
    }: {
        buildId: string,
        requestBody: CreateRuntimeReleaseRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<RuntimeReleaseRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/runtime-builds/{buildId}:promote',
            path: {
                'buildId': buildId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Request a dual-control Runtime disable
     * @returns RuntimeReleaseRequest Disable request awaiting a second Platform Admin.
     * @throws ApiError
     */
    public requestRuntimeDisable({
        buildId,
        requestBody,
        xTenantId,
        xActorId,
    }: {
        buildId: string,
        requestBody: CreateRuntimeDisableRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<RuntimeReleaseRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/runtime-builds/{buildId}:disable',
            path: {
                'buildId': buildId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List Runtime release decisions
     * @returns RuntimeReleaseRequestListResponse Platform release requests visible to the current control tenant.
     * @throws ApiError
     */
    public listRuntimeReleaseRequests({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RuntimeReleaseRequestListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/runtime-release-requests',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Approve a Runtime release decision as the second Platform Admin
     * @returns RuntimeReleaseRequest Approved release decision and evidence hash.
     * @throws ApiError
     */
    public approveRuntimeRelease({
        releaseId,
        xTenantId,
        xActorId,
    }: {
        releaseId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<RuntimeReleaseRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/runtime-release-requests/{releaseId}:approve',
            path: {
                'releaseId': releaseId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject a pending Runtime release decision
     * @returns RuntimeReleaseRequest Rejected release decision.
     * @throws ApiError
     */
    public rejectRuntimeRelease({
        releaseId,
        xTenantId,
        xActorId,
    }: {
        releaseId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<RuntimeReleaseRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/runtime-release-requests/{releaseId}:reject',
            path: {
                'releaseId': releaseId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List Build-bound Runtime Validation runs
     * @returns RuntimeValidation Validation runs.
     * @throws ApiError
     */
    public listRuntimeValidations(): CancelablePromise<Array<RuntimeValidation>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/runtime-validations',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Start a Build/environment/dataset-bound Runtime Validation
     * @returns RuntimeValidation Validation started.
     * @throws ApiError
     */
    public startRuntimeValidation({
        requestBody,
    }: {
        requestBody: StartRuntimeValidationRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validations',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Commit immutable Runtime Validation evidence
     * @returns RuntimeValidation Validation completed.
     * @throws ApiError
     */
    public completeRuntimeValidation({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: CompleteRuntimeValidationRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validations/{validationId}:complete',
            path: {
                'validationId': validationId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                404: `Resource not found.`,
            },
        });
    }
}
