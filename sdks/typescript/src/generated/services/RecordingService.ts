/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CreateRecordingPlaybackGrantRequest } from '../models/CreateRecordingPlaybackGrantRequest.js';
import type { RecordingList } from '../models/RecordingList.js';
import type { RecordingPlaybackAccess } from '../models/RecordingPlaybackAccess.js';
import type { RecordingPlaybackGrant } from '../models/RecordingPlaybackGrant.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class RecordingService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List immutable Session recording manifests
     * Lists the tenant-scoped PostgreSQL projection of Browser Node-authoritative recording manifests. Internal object-storage coordinates and raw recording bytes are not returned.
     *
     * @returns RecordingList Recording manifests ordered newest first.
     * @throws ApiError
     */
    public listSessionRecordings({
        sessionId,
        xTenantId,
        limit = 50,
        offset,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
        offset?: number,
    }): CancelablePromise<RecordingList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/recordings',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
                'offset': offset,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Create a purpose-bound recording playback grant
     * Requires a tenant/security/platform administrator. Creates an actor-, tenant-, Session-, recording- and purpose-bound grant that expires after five minutes. The recording must still be retained or held. Object-storage coordinates and signed URLs are not persisted or returned by this operation.
     *
     * @returns RecordingPlaybackGrant Purpose-bound playback grant created or idempotently replayed.
     * @throws ApiError
     */
    public createRecordingPlaybackGrant({
        sessionId,
        idempotencyKey,
        recordingId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        recordingId: string,
        requestBody: CreateRecordingPlaybackGrantRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecordingPlaybackGrant> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/recordings/{recordingId}/playback-grants',
            path: {
                'sessionId': sessionId,
                'recordingId': recordingId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
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
     * Redeem a recording playback grant once
     * The issuing actor may redeem an unexpired grant exactly once. Browser Node and Storage Helper verify the aggregate manifest and every returned segment commit marker before issuing 60-second URLs. At most 24 segments are returned; nextSegmentOffset continues the same five-minute actor-bound playback window without redeeming the grant again.
     *
     * @returns RecordingPlaybackAccess First integrity-verified segment page with ephemeral URLs.
     * @throws ApiError
     */
    public redeemRecordingPlaybackGrant({
        sessionId,
        grantId,
        xTenantId,
    }: {
        sessionId: string,
        grantId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecordingPlaybackAccess> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/recording-playback-grants/{grantId}:redeem',
            path: {
                'sessionId': sessionId,
                'grantId': grantId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
                502: `Browser Node or Object Storage rejected the integrity-bound playback request.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Get a bounded recording segment page
     * Signs a bounded segment page only for the same actor while the redeemed five-minute access window and recording retention/Legal Hold state remain valid. The offset must be smaller than the immutable segment count; callers may re-request a page after an interrupted download. URLs expire after 60 seconds and are never stored.
     *
     * @returns RecordingPlaybackAccess Integrity-verified segment page with ephemeral URLs.
     * @throws ApiError
     */
    public getRecordingPlaybackSegments({
        sessionId,
        grantId,
        offset,
        xTenantId,
    }: {
        sessionId: string,
        grantId: string,
        offset: number,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecordingPlaybackAccess> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/recording-playback-grants/{grantId}/segments',
            path: {
                'sessionId': sessionId,
                'grantId': grantId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'offset': offset,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
                502: `Browser Node or Object Storage rejected the integrity-bound playback request.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
}
