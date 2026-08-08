/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CreateEnvironmentSavedViewRequest } from '../models/CreateEnvironmentSavedViewRequest.js';
import type { EnvironmentSavedView } from '../models/EnvironmentSavedView.js';
import type { EnvironmentSavedViewListResponse } from '../models/EnvironmentSavedViewListResponse.js';
import type { UpdateEnvironmentSavedViewRequest } from '../models/UpdateEnvironmentSavedViewRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class SavedViewService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List Workspace views and the current actor's Personal views
     * @returns EnvironmentSavedViewListResponse Tenant-scoped visible Environment Saved Views.
     * @throws ApiError
     */
    public listEnvironmentSavedViews(): CancelablePromise<EnvironmentSavedViewListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/environment-saved-views',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Create an audited Environment filter and column preset
     * Operators may create Personal views. Workspace views require an administrator role.
     * @returns EnvironmentSavedView Saved View created.
     * @throws ApiError
     */
    public createEnvironmentSavedView({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: CreateEnvironmentSavedViewRequest,
    }): CancelablePromise<EnvironmentSavedView> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/environment-saved-views',
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Update a Personal owner view or an administrator-governed Workspace view
     * @returns EnvironmentSavedView Saved View updated.
     * @throws ApiError
     */
    public updateEnvironmentSavedView({
        savedViewId,
        idempotencyKey,
        requestBody,
    }: {
        savedViewId: string,
        idempotencyKey: string,
        requestBody: UpdateEnvironmentSavedViewRequest,
    }): CancelablePromise<EnvironmentSavedView> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/environment-saved-views/{savedViewId}',
            path: {
                'savedViewId': savedViewId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Delete a Saved View with optimistic concurrency
     * @returns void
     * @throws ApiError
     */
    public deleteEnvironmentSavedView({
        savedViewId,
        idempotencyKey,
        expectedVersion,
    }: {
        savedViewId: string,
        idempotencyKey: string,
        expectedVersion: number,
    }): CancelablePromise<void> {
        return this.httpRequest.request({
            method: 'DELETE',
            url: '/api/v1/environment-saved-views/{savedViewId}',
            path: {
                'savedViewId': savedViewId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            query: {
                'expectedVersion': expectedVersion,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
}
