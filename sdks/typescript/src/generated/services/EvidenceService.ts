/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CaptureEvidenceRequest } from '../models/CaptureEvidenceRequest.js';
import type { CreateEvidenceAccessGrantRequest } from '../models/CreateEvidenceAccessGrantRequest.js';
import type { EvidenceAccessGrant } from '../models/EvidenceAccessGrant.js';
import type { EvidenceCapture } from '../models/EvidenceCapture.js';
import type { EvidenceList } from '../models/EvidenceList.js';
import type { RedeemEvidenceAccessResponse } from '../models/RedeemEvidenceAccessResponse.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class EvidenceService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List tenant-scoped real screenshot evidence metadata
     * Lists the durable metadata index for real CDP screenshots committed through the privileged Storage Helper. Raw pixels and internal object-storage keys are not returned.
     *
     * @returns EvidenceList Screenshot evidence metadata ordered newest first.
     * @throws ApiError
     */
    public listSessionEvidence({
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
    }): CancelablePromise<EvidenceList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/evidence',
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
     * Request an administrator-governed real Observer screenshot
     * Requires a tenant/security/platform administrator. The request is durable and idempotent; EXECUTING becomes COMMITTED or FAILED only after the Browser Node submits real evidence or the command is dead-lettered. Capture is rejected during HumanTakeover. No screenshot bytes cross this API.
     *
     * @returns EvidenceCapture Durable capture request accepted.
     * @throws ApiError
     */
    public captureSessionEvidence({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CaptureEvidenceRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<EvidenceCapture> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/evidence:capture',
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
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Read the durable Observer capture request state
     * @returns EvidenceCapture Current capture request state.
     * @throws ApiError
     */
    public getSessionEvidenceCapture({
        sessionId,
        captureId,
        xTenantId,
    }: {
        sessionId: string,
        captureId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<EvidenceCapture> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/evidence-captures/{captureId}',
            path: {
                'sessionId': sessionId,
                'captureId': captureId,
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
     * Create a purpose-bound one-time evidence access grant
     * Requires a tenant/security/platform administrator. The grant is bound to the authenticated actor, Session, immutable evidence object and declared purpose, and expires within five minutes. It does not contain storage coordinates or a signed URL.
     *
     * @returns EvidenceAccessGrant One-time access grant issued.
     * @throws ApiError
     */
    public createSessionEvidenceAccessGrant({
        sessionId,
        idempotencyKey,
        evidenceId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        evidenceId: string,
        requestBody: CreateEvidenceAccessGrantRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<EvidenceAccessGrant> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/evidence/{evidenceId}/access-grants',
            path: {
                'sessionId': sessionId,
                'evidenceId': evidenceId,
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
     * Redeem an evidence access grant exactly once
     * Only the actor who created the unexpired grant may redeem it. The Browser Node signs one exact immutable screenshot object for 60 seconds over internal mTLS. The returned URL is ephemeral and is never persisted or written to audit logs.
     *
     * @returns RedeemEvidenceAccessResponse Ephemeral exact-object access.
     * @throws ApiError
     */
    public redeemSessionEvidenceAccessGrant({
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
    }): CancelablePromise<RedeemEvidenceAccessResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/evidence-access-grants/{grantId}:redeem',
            path: {
                'sessionId': sessionId,
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
}
