/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CostRate } from '../models/CostRate.js';
import type { CreateCostRateRequest } from '../models/CreateCostRateRequest.js';
import type { MediaQuota } from '../models/MediaQuota.js';
import type { SessionCostExplanation } from '../models/SessionCostExplanation.js';
import type { UpsertMediaQuotaRequest } from '../models/UpsertMediaQuotaRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class CostService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List versioned scheduler cost rates
     * @returns CostRate Cost rates.
     * @throws ApiError
     */
    public listEnterpriseCostRates(): CancelablePromise<Array<CostRate>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/cost-rates',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Create an immutable effective-dated cost rate
     * @returns CostRate Cost rate created.
     * @throws ApiError
     */
    public createEnterpriseCostRate({
        requestBody,
    }: {
        requestBody: CreateCostRateRequest,
    }): CancelablePromise<CostRate> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/cost-rates',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Recompute the hourly Session cost from its Placement and pricing version
     * @returns SessionCostExplanation Cost components and total.
     * @throws ApiError
     */
    public explainSessionCost({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionCostExplanation> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/sessions/{sessionId}/cost-explanation',
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
     * Read independent tenant encoder-stream and bitrate limits
     * @returns MediaQuota Media quota and current active usage.
     * @throws ApiError
     */
    public getTenantMediaQuota(): CancelablePromise<MediaQuota> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/media-quota',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Configure independent tenant encoder-stream and bitrate limits
     * @returns MediaQuota Updated media quota and current usage.
     * @throws ApiError
     */
    public upsertTenantMediaQuota({
        requestBody,
    }: {
        requestBody: UpsertMediaQuotaRequest,
    }): CancelablePromise<MediaQuota> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/media-quota',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
}
