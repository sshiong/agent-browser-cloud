/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { SessionIdentityChangeRequest } from '../models/SessionIdentityChangeRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class AdministrationService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Approve a tenant Session identity change
     * @returns SessionIdentityChangeRequest Decided change request.
     * @throws ApiError
     */
    public approveSessionIdentityChangeRequest({
        requestId,
        xTenantId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentityChangeRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/session-identity-change-requests/{requestId}:approve',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject a tenant Session identity change
     * @returns SessionIdentityChangeRequest Decided change request.
     * @throws ApiError
     */
    public rejectSessionIdentityChangeRequest({
        requestId,
        xTenantId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentityChangeRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/session-identity-change-requests/{requestId}:reject',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Apply an approved identity change while the Runtime is safely stopped
     * @returns SessionIdentityChangeRequest Applied change request.
     * @throws ApiError
     */
    public applySessionIdentityChangeRequest({
        requestId,
        xTenantId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentityChangeRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/session-identity-change-requests/{requestId}:apply',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
}
