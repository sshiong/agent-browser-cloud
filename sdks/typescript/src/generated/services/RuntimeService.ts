/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ClaimRuntimeValidationJobRequest } from '../models/ClaimRuntimeValidationJobRequest.js';
import type { CompleteRuntimeValidationJobRequest } from '../models/CompleteRuntimeValidationJobRequest.js';
import type { CompleteRuntimeValidationRequest } from '../models/CompleteRuntimeValidationRequest.js';
import type { CreateRuntimeDisableRequest } from '../models/CreateRuntimeDisableRequest.js';
import type { CreateRuntimeReleaseRequest } from '../models/CreateRuntimeReleaseRequest.js';
import type { FailRuntimeValidationJobRequest } from '../models/FailRuntimeValidationJobRequest.js';
import type { ReleaseFreeze } from '../models/ReleaseFreeze.js';
import type { RuntimeBuildListResponse } from '../models/RuntimeBuildListResponse.js';
import type { RuntimeReleaseRequest } from '../models/RuntimeReleaseRequest.js';
import type { RuntimeReleaseRequestListResponse } from '../models/RuntimeReleaseRequestListResponse.js';
import type { RuntimeValidation } from '../models/RuntimeValidation.js';
import type { RuntimeValidationJob } from '../models/RuntimeValidationJob.js';
import type { RuntimeValidationJobClaim } from '../models/RuntimeValidationJobClaim.js';
import type { RuntimeValidationJobClaimRequest } from '../models/RuntimeValidationJobClaimRequest.js';
import type { StartRuntimeValidationMatrixRequest } from '../models/StartRuntimeValidationMatrixRequest.js';
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
    /**
     * Enqueue an immutable browser/OS Runtime Validation matrix
     * Requires PLATFORM_ADMIN. Every matrix cell becomes an independently leased job.
     * @returns RuntimeValidation Matrix cells durably enqueued.
     * @throws ApiError
     */
    public startRuntimeValidationMatrix({
        requestBody,
    }: {
        requestBody: StartRuntimeValidationMatrixRequest,
    }): CancelablePromise<Array<RuntimeValidation>> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-matrices',
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
     * Claim one compatible Runtime Validation job with a fenced lease
     * Requires the dedicated VALIDATION_WORKER role. A claim token is returned only once.
     * @returns RuntimeValidationJobClaim Compatible job claimed.
     * @throws ApiError
     */
    public claimRuntimeValidationJob({
        requestBody,
    }: {
        requestBody: ClaimRuntimeValidationJobRequest,
    }): CancelablePromise<RuntimeValidationJobClaim> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs:claim',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * ACK execution start for a claimed Runtime Validation job
     * @returns RuntimeValidationJob Job entered EXECUTING.
     * @throws ApiError
     */
    public startClaimedRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: RuntimeValidationJobClaimRequest,
    }): CancelablePromise<RuntimeValidationJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:start',
            path: {
                'validationId': validationId,
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
     * Renew a fenced Runtime Validation Worker lease
     * @returns RuntimeValidationJob Lease renewed.
     * @throws ApiError
     */
    public heartbeatRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: RuntimeValidationJobClaimRequest,
    }): CancelablePromise<RuntimeValidationJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:heartbeat',
            path: {
                'validationId': validationId,
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
     * ACK and atomically commit a Runtime Validation Worker result
     * @returns RuntimeValidation Result committed and Runtime Build status updated.
     * @throws ApiError
     */
    public completeRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: CompleteRuntimeValidationJobRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:complete',
            path: {
                'validationId': validationId,
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
     * Reject a Runtime Validation attempt and retry or quarantine the build
     * @returns RuntimeValidation Failure durably requeued or finalized.
     * @throws ApiError
     */
    public failRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: FailRuntimeValidationJobRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:fail',
            path: {
                'validationId': validationId,
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
     * Read the authoritative Error Budget release gate
     * Returns the PostgreSQL-backed automatic Runtime promotion freeze state. Emergency Runtime disable operations remain available while the promotion gate is frozen.
     *
     * @returns ReleaseFreeze Current automatic release gate and hysteresis state.
     * @throws ApiError
     */
    public getReleaseFreezeState({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ReleaseFreeze> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/release-freeze',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
}
