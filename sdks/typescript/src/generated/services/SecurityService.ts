/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BreakGlassRequest } from '../models/BreakGlassRequest.js';
import type { BreakGlassRequestListResponse } from '../models/BreakGlassRequestListResponse.js';
import type { CompleteKeyRotationRequest } from '../models/CompleteKeyRotationRequest.js';
import type { CreateBreakGlassRequest } from '../models/CreateBreakGlassRequest.js';
import type { CreateKeyRotationRequest } from '../models/CreateKeyRotationRequest.js';
import type { KeyRotationRequest } from '../models/KeyRotationRequest.js';
import type { KeyRotationRequestListResponse } from '../models/KeyRotationRequestListResponse.js';
import type { SecureDebugSession } from '../models/SecureDebugSession.js';
import type { SecureDebugSessionListResponse } from '../models/SecureDebugSessionListResponse.js';
import type { SecureDebugSnapshot } from '../models/SecureDebugSnapshot.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class SecurityService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List platform key rotation requests
     * @returns KeyRotationRequestListResponse Key rotation lifecycle records for the current control tenant.
     * @throws ApiError
     */
    public listKeyRotationRequests({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<KeyRotationRequestListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/key-rotation-requests',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Request a dual-control key rotation
     * @returns KeyRotationRequest Rotation request awaiting a second Platform Admin.
     * @throws ApiError
     */
    public requestKeyRotation({
        requestBody,
        xTenantId,
        xActorId,
    }: {
        requestBody: CreateKeyRotationRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<KeyRotationRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/key-rotation-requests',
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
     * Approve and start a key rotation
     * @returns KeyRotationRequest Rotation entered dual-read and single-write execution.
     * @throws ApiError
     */
    public approveKeyRotation({
        rotationId,
        xTenantId,
        xActorId,
    }: {
        rotationId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<KeyRotationRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/key-rotation-requests/{rotationId}:approve',
            path: {
                'rotationId': rotationId,
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
     * Complete a verified key rotation
     * @returns KeyRotationRequest Completed rotation with immutable verification evidence.
     * @throws ApiError
     */
    public completeKeyRotation({
        rotationId,
        requestBody,
        xTenantId,
        xActorId,
    }: {
        rotationId: string,
        requestBody: CompleteKeyRotationRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<KeyRotationRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/key-rotation-requests/{rotationId}:complete',
            path: {
                'rotationId': rotationId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
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
     * Revoke a requested or in-progress key rotation
     * @returns KeyRotationRequest Revoked key rotation.
     * @throws ApiError
     */
    public revokeKeyRotation({
        rotationId,
        xTenantId,
        xActorId,
    }: {
        rotationId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<KeyRotationRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/key-rotation-requests/{rotationId}:revoke',
            path: {
                'rotationId': rotationId,
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
     * List tenant emergency-access requests
     * @returns BreakGlassRequestListResponse Tenant-scoped break-glass requests.
     * @throws ApiError
     */
    public listBreakGlassRequests({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<BreakGlassRequestListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/break-glass-requests',
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
     * Request time-bound emergency access
     * @returns BreakGlassRequest Pending request awaiting a second Security Admin.
     * @throws ApiError
     */
    public requestBreakGlass({
        requestBody,
        xTenantId,
        xActorId,
    }: {
        requestBody: CreateBreakGlassRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<BreakGlassRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/break-glass-requests',
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Activate a request using a distinct Security Admin
     * @returns BreakGlassRequest Active time-bound grant.
     * @throws ApiError
     */
    public approveBreakGlass({
        requestId,
        xTenantId,
        xActorId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<BreakGlassRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/break-glass-requests/{requestId}:approve',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject a pending emergency-access request
     * @returns BreakGlassRequest Rejected request.
     * @throws ApiError
     */
    public rejectBreakGlass({
        requestId,
        xTenantId,
        xActorId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<BreakGlassRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/break-glass-requests/{requestId}:reject',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Revoke active emergency access
     * @returns BreakGlassRequest Revoked or expired request.
     * @throws ApiError
     */
    public revokeBreakGlass({
        requestId,
        xTenantId,
        xActorId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<BreakGlassRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/break-glass-requests/{requestId}:revoke',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Record the mandatory post-access review
     * @returns BreakGlassRequest Terminal request with review evidence.
     * @throws ApiError
     */
    public reviewBreakGlass({
        requestId,
        xTenantId,
        xActorId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<BreakGlassRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/break-glass-requests/{requestId}:review',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Consume an approved grant to start one purpose-limited debug session
     * @returns SecureDebugSession Active Secure Debug session, capped at fifteen minutes.
     * @throws ApiError
     */
    public startSecureDebug({
        requestId,
        xTenantId,
        xActorId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<SecureDebugSession> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/break-glass-requests/{requestId}:start-secure-debug',
            path: {
                'requestId': requestId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List tenant Secure Debug sessions and their evidence heads
     * @returns SecureDebugSessionListResponse Tenant-scoped Secure Debug sessions.
     * @throws ApiError
     */
    public listSecureDebugSessions({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SecureDebugSessionListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/secure-debug-sessions',
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
     * Read the current purpose-limited diagnostic projection
     * Full URL/query, title, DOM text, target names/bounds, screenshots, cookies and Profile content are never returned.
     * @returns SecureDebugSnapshot Minimized snapshot with an append-only access evidence hash.
     * @throws ApiError
     */
    public readSecureDebugSnapshot({
        debugSessionId,
        xTenantId,
        xActorId,
    }: {
        debugSessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<SecureDebugSnapshot> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/secure-debug-sessions/{debugSessionId}/snapshot',
            path: {
                'debugSessionId': debugSessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * End a Secure Debug session and seal its evidence chain
     * @returns SecureDebugSession Terminal Secure Debug session.
     * @throws ApiError
     */
    public endSecureDebug({
        debugSessionId,
        xTenantId,
        xActorId,
    }: {
        debugSessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<SecureDebugSession> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/secure-debug-sessions/{debugSessionId}:end',
            path: {
                'debugSessionId': debugSessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                401: `Missing or invalid authenticated principal.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
}
