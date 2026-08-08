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
}
