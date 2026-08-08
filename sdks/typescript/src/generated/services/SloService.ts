/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ErrorBudget } from '../models/ErrorBudget.js';
import type { RecordServiceLevelEventRequest } from '../models/RecordServiceLevelEventRequest.js';
import type { SlaExclusion } from '../models/SlaExclusion.js';
import type { UpsertSlaExclusionRequest } from '../models/UpsertSlaExclusionRequest.js';
import type { UpsertSloPolicyRequest } from '../models/UpsertSloPolicyRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class SloService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Configure the tenant SLO and error-budget window
     * @returns ErrorBudget Current error budget.
     * @throws ApiError
     */
    public upsertSloPolicy({
        requestBody,
        xTenantId,
    }: {
        requestBody: UpsertSloPolicyRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ErrorBudget> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/slo-policy',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Calculate the tenant error budget
     * @returns ErrorBudget Current error budget.
     * @throws ApiError
     */
    public getErrorBudget({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ErrorBudget> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/error-budget',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Record a bounded service-level observation
     * @returns ErrorBudget Recalculated error budget.
     * @throws ApiError
     */
    public recordServiceLevelEvent({
        requestBody,
        xTenantId,
    }: {
        requestBody: RecordServiceLevelEventRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ErrorBudget> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/service-level-events',
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
     * List explicit contract exclusions used by Error Budget accounting
     * @returns SlaExclusion SLA exclusions.
     * @throws ApiError
     */
    public listSlaExclusions(): CancelablePromise<Array<SlaExclusion>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/sla-exclusions',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Configure an explicit SLA exclusion
     * @returns SlaExclusion Updated SLA exclusion.
     * @throws ApiError
     */
    public upsertSlaExclusion({
        exclusionCode,
        requestBody,
    }: {
        exclusionCode: string,
        requestBody: UpsertSlaExclusionRequest,
    }): CancelablePromise<SlaExclusion> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/sla-exclusions/{exclusionCode}',
            path: {
                'exclusionCode': exclusionCode,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
}
