/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CreateSafetyLeaseRequest } from '../models/CreateSafetyLeaseRequest.js';
import type { RenewSafetyLeaseRequest } from '../models/RenewSafetyLeaseRequest.js';
import type { ResourceEventList } from '../models/ResourceEventList.js';
import type { ResourcePolicyOperation } from '../models/ResourcePolicyOperation.js';
import type { ResourcePolicyRequest } from '../models/ResourcePolicyRequest.js';
import type { SafetyLease } from '../models/SafetyLease.js';
import type { SafetyLeaseList } from '../models/SafetyLeaseList.js';
import type { SessionMigration } from '../models/SessionMigration.js';
import type { SessionResource } from '../models/SessionResource.js';
import type { SessionSafePoint } from '../models/SessionSafePoint.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class ResourceService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Get the authoritative Session resource policy, allocation and real telemetry
     * @returns SessionResource Resource state. Usage is null until Browser Node telemetry exists.
     * @throws ApiError
     */
    public getSessionResources({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionResource> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/resources',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * List the PostgreSQL-backed resource adjustment timeline
     * @returns ResourceEventList Resource policy and adjustment events.
     * @throws ApiError
     */
    public listSessionResourceEvents({
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
    }): CancelablePromise<ResourceEventList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/resource-events',
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
     * Stream durable Session resource changes with resumable SSE
     * Emits PostgreSQL-backed resource sample, adjustment and application safety lease change notifications. Reconnect with Last-Event-ID to replay changes after the last processed monotonic sequence. The event data identifies the durable row; clients then refresh the authoritative resource, timeline, safe-point and migration views.
     *
     * @returns string SSE stream. Events are resource-stream-ready, resource-stream-reset and session-resource-change; keepalive comments do not advance the cursor.
     *
     * @throws ApiError
     */
    public streamSessionResourceChanges({
        sessionId,
        xTenantId,
        lastEventId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Last successfully processed numeric resource stream sequence.
         */
        lastEventId?: string,
    }): CancelablePromise<string> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/resource-stream',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Last-Event-ID': lastEventId,
            },
            errors: {
                400: `Invalid request.`,
                404: `Resource not found.`,
                429: `Per-process or per-Session live subscriber bound reached.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Assess whether a Session is at a migration-safe point
     * Fail-closed aggregation of fresh Browser Node input observations and durable control-plane blockers.
     * @returns SessionSafePoint Tenant-scoped safe-point assessment.
     * @throws ApiError
     */
    public getSessionSafePoint({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionSafePoint> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/safe-point',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Acquire an application business-activity Safe Point lease
     * Application adapters acquire a short owner-bound lease before file transfer, SPA/form submission, payment/account-security work, critical transactions or while business recovery is unknown. Active leases block migration and hibernation.
     *
     * @returns SafetyLease Lease acquired or an idempotent acquisition replayed.
     * @throws ApiError
     */
    public acquireSessionSafetyLease({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateSafetyLeaseRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SafetyLease> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/safety-leases',
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
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List the latest durable application Safe Point leases for a Session
     * @returns SafetyLeaseList Bounded latest current and terminal lease records plus the authoritative total.
     * @throws ApiError
     */
    public listSessionSafetyLeases({
        sessionId,
        xTenantId,
        limit = 50,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
    }): CancelablePromise<SafetyLeaseList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/safety-leases',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Renew an owned active application Safe Point lease
     * @returns SafetyLease Lease renewed or an idempotent renewal replayed.
     * @throws ApiError
     */
    public renewSessionSafetyLease({
        sessionId,
        leaseId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        leaseId: string,
        idempotencyKey: string,
        requestBody: RenewSafetyLeaseRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SafetyLease> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/sessions/{sessionId}/safety-leases/{leaseId}',
            path: {
                'sessionId': sessionId,
                'leaseId': leaseId,
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
     * Release an owned application Safe Point lease
     * @returns SafetyLease Terminal lease state.
     * @throws ApiError
     */
    public releaseSessionSafetyLease({
        sessionId,
        leaseId,
        idempotencyKey,
        xTenantId,
    }: {
        sessionId: string,
        leaseId: string,
        idempotencyKey: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SafetyLease> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/safety-leases/{leaseId}:release',
            path: {
                'sessionId': sessionId,
                'leaseId': leaseId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Get the latest durable cross-node migration workflow
     * @returns SessionMigration Latest migration phase and recovery result.
     * @throws ApiError
     */
    public getLatestSessionMigration({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionMigration> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/migration',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Update AUTO resource policy through an idempotent backend Operation
     * @returns ResourcePolicyOperation Resource policy Operation committed.
     * @throws ApiError
     */
    public updateSessionResourcePolicy({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: ResourcePolicyRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ResourcePolicyOperation> {
        return this.httpRequest.request({
            method: 'PATCH',
            url: '/api/v1/sessions/{sessionId}/resource-policy',
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
                409: `State or idempotency conflict.`,
            },
        });
    }
}
