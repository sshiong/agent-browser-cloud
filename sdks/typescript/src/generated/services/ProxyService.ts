/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProxyBinding } from '../models/ProxyBinding.js';
import type { ProxyBindingList } from '../models/ProxyBindingList.js';
import type { ProxyBindingRequest } from '../models/ProxyBindingRequest.js';
import type { ProxyOverview } from '../models/ProxyOverview.js';
import type { ProxyRebind } from '../models/ProxyRebind.js';
import type { ProxyRebindOperation } from '../models/ProxyRebindOperation.js';
import type { ProxyRebindRequest } from '../models/ProxyRebindRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class ProxyService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Safely restart a Session on a new Proxy Binding
     * Requires a durable Safe Point, checkpoints and stops the Browser, commits the target binding snapshot only after the source allocation is released, restores the Browser, performs State Resync and then validates Business Recovery. This is not an in-place or connection-transparent proxy change.
     *
     * @returns ProxyRebindOperation Durable proxy rebind workflow accepted or idempotently replayed.
     * @throws ApiError
     */
    public rebindSessionProxy({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: ProxyRebindRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProxyRebindOperation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/proxy-binding:rebind',
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
     * Get the latest durable proxy rebind workflow
     * @returns ProxyRebind Latest Safe Point controlled proxy rebind status.
     * @throws ApiError
     */
    public getLatestSessionProxyRebind({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProxyRebind> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/proxy-rebind',
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
     * Get the static Proxy Provider and tenant allocation ledger
     * @returns ProxyOverview Provider configuration and tenant-scoped allocations.
     * @throws ApiError
     */
    public getProxyOverview({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProxyOverview> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/proxies',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * List reusable tenant Proxy Binding profiles
     * @returns ProxyBindingList Tenant-scoped reusable configuration. Secret references are never returned.
     * @throws ApiError
     */
    public listProxyBindings(): CancelablePromise<ProxyBindingList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/proxy-bindings',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Create a reusable tenant Proxy Binding profile
     * @returns ProxyBinding Binding profile created.
     * @throws ApiError
     */
    public createProxyBinding({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: ProxyBindingRequest,
    }): CancelablePromise<ProxyBinding> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/proxy-bindings',
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
    /**
     * Update a reusable Proxy Binding profile using version compare-and-swap
     * @returns ProxyBinding Binding profile updated.
     * @throws ApiError
     */
    public updateProxyBinding({
        bindingProfileId,
        idempotencyKey,
        requestBody,
    }: {
        bindingProfileId: string,
        idempotencyKey: string,
        requestBody: ProxyBindingRequest,
    }): CancelablePromise<ProxyBinding> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/proxy-bindings/{bindingProfileId}',
            path: {
                'bindingProfileId': bindingProfileId,
            },
            headers: {
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
     * Delete an unused Proxy Binding profile
     * @returns void
     * @throws ApiError
     */
    public deleteProxyBinding({
        bindingProfileId,
        idempotencyKey,
    }: {
        bindingProfileId: string,
        idempotencyKey: string,
    }): CancelablePromise<void> {
        return this.httpRequest.request({
            method: 'DELETE',
            url: '/api/v1/proxy-bindings/{bindingProfileId}',
            path: {
                'bindingProfileId': bindingProfileId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
}
