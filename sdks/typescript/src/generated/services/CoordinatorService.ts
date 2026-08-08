/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RequestTenantRouteMigration } from '../models/RequestTenantRouteMigration.js';
import type { TenantRoute } from '../models/TenantRoute.js';
import type { TenantRouteMigration } from '../models/TenantRouteMigration.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class CoordinatorService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Get the authenticated tenant's authoritative Coordinator route
     * @returns TenantRoute Current active and pending tenant route epochs.
     * @throws ApiError
     */
    public getTenantCoordinatorRoute(): CancelablePromise<TenantRoute> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/coordinator/tenant-route',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Get the latest durable tenant route migration
     * @returns TenantRouteMigration Latest migration and safe-point reconciliation progress.
     * @throws ApiError
     */
    public getLatestTenantCoordinatorRouteMigration(): CancelablePromise<TenantRouteMigration> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/coordinator/tenant-route/migration',
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Repartition a hot tenant using safe-point migration and Route Epoch fencing
     * @returns TenantRouteMigration Durable migration accepted or idempotently replayed.
     * @throws ApiError
     */
    public requestTenantCoordinatorRouteMigration({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: RequestTenantRouteMigration,
    }): CancelablePromise<TenantRouteMigration> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/coordinator/tenant-route/migrations',
            headers: {
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
