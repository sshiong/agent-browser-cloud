/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AuditEventListResponse } from '../models/AuditEventListResponse.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class AuditService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List and verify the tenant tamper-evident audit chain
     * Requires the SECURITY_ADMIN role. Details are redacted before persistence.
     * @returns AuditEventListResponse Tenant-scoped audit evidence and full-chain verification result.
     * @throws ApiError
     */
    public listAuditEvents({
        xTenantId,
        sessionId,
        eventType,
        limit = 100,
        offset,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        sessionId?: string,
        eventType?: string,
        limit?: number,
        offset?: number,
    }): CancelablePromise<AuditEventListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/audit-events',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'sessionId': sessionId,
                'eventType': eventType,
                'limit': limit,
                'offset': offset,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Stream resumable invalidations for the tenant audit chain
     * Requires the SECURITY_ADMIN role. Emits payload-free change notifications whose id is the tenant audit chain sequence. Every audited row advances the cursor, so a client that resumes with Last-Event-ID and refetches the authorized projection cannot miss a change.
     *
     * @returns string Resumable tenant audit Server-Sent Event stream.
     * @throws ApiError
     */
    public streamAuditEventChanges({
        xTenantId,
        lastEventId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        lastEventId?: number,
    }): CancelablePromise<string> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/audit-events/event-stream',
            headers: {
                'X-Tenant-Id': xTenantId,
                'Last-Event-ID': lastEventId,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                429: `The bounded concurrent stream capacity has been reached.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
}
