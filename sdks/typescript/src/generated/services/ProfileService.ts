/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CreateProfileExportGrantRequest } from '../models/CreateProfileExportGrantRequest.js';
import type { CreateProfileRequest } from '../models/CreateProfileRequest.js';
import type { Profile } from '../models/Profile.js';
import type { ProfileExportGrant } from '../models/ProfileExportGrant.js';
import type { ProfileImport } from '../models/ProfileImport.js';
import type { ProfileImportListResponse } from '../models/ProfileImportListResponse.js';
import type { ProfileListResponse } from '../models/ProfileListResponse.js';
import type { RedeemProfileExportResponse } from '../models/RedeemProfileExportResponse.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class ProfileService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List tenant Profiles and their latest committed checkpoints
     * @returns ProfileListResponse Tenant-scoped Profile list.
     * @throws ApiError
     */
    public listProfiles({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProfileListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/profiles',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Create a persistent browser Profile
     * @returns Profile Profile created.
     * @throws ApiError
     */
    public createProfile({
        requestBody,
        xTenantId,
    }: {
        requestBody: CreateProfileRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<Profile> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/profiles',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Get Profile checkpoint metadata
     * @returns Profile Tenant-scoped Profile.
     * @throws ApiError
     */
    public getProfile({
        profileId,
        xTenantId,
    }: {
        profileId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<Profile> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/profiles/{profileId}',
            path: {
                'profileId': profileId,
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
     * Create a five-minute, actor-owned grant for the latest committed checkpoint
     * @returns ProfileExportGrant Purpose-bound export grant created.
     * @throws ApiError
     */
    public createProfileExportGrant({
        profileId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        profileId: string,
        idempotencyKey: string,
        requestBody: CreateProfileExportGrantRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProfileExportGrant> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/profiles/{profileId}/export-grants',
            path: {
                'profileId': profileId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
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
     * Redeem an export grant exactly once for a 60-second signed URL
     * @returns RedeemProfileExportResponse Ephemeral signed checkpoint archive download.
     * @throws ApiError
     */
    public redeemProfileExportGrant({
        profileId,
        grantId,
        xTenantId,
    }: {
        profileId: string,
        grantId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RedeemProfileExportResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/profiles/{profileId}/export-grants/{grantId}:redeem',
            path: {
                'profileId': profileId,
                'grantId': grantId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * List the current actor's durable Profile Import jobs
     * @returns ProfileImportListResponse Actor-owned Profile Import jobs.
     * @throws ApiError
     */
    public listProfileImports({
        xTenantId,
        limit = 20,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
    }): CancelablePromise<ProfileImportListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/profile-imports',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Stream and import a native Profile checkpoint archive
     * After bounded multipart ingress, the Control Plane forwards archive bytes over mTLS and never stores them in PostgreSQL or durable business storage. A capability-advertising Browser Node and isolated Storage Helper independently verify SHA-256, normalize tenant/Profile identity and commit Object Storage before PostgreSQL Profile metadata becomes visible.
     *
     * @returns ProfileImport Archive validated and Profile checkpoint committed.
     * @throws ApiError
     */
    public importProfileCheckpoint({
        idempotencyKey,
        formData,
        xTenantId,
        xActorId,
    }: {
        idempotencyKey: string,
        formData: {
            profileId: string;
            profileName: string;
            profileDescription?: string;
            runtimeBuildId: string;
            archiveSha256: string;
            archive: Blob;
        },
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<ProfileImport> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/profile-imports',
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
                'Idempotency-Key': idempotencyKey,
            },
            formData: formData,
            mediaType: 'multipart/form-data',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
                413: `The upload exceeds the configured bounded ingress limit.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Get one actor-owned Profile Import job
     * @returns ProfileImport Durable Profile Import job.
     * @throws ApiError
     */
    public getProfileImport({
        importId,
        xTenantId,
    }: {
        importId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProfileImport> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/profile-imports/{importId}',
            path: {
                'importId': importId,
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
}
