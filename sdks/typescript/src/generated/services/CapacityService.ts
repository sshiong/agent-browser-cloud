/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BrowserNode } from '../models/BrowserNode.js';
import type { BrowserNodeListResponse } from '../models/BrowserNodeListResponse.js';
import type { BrowserPlacement } from '../models/BrowserPlacement.js';
import type { ExtensionProfile } from '../models/ExtensionProfile.js';
import type { ExtensionProfileListResponse } from '../models/ExtensionProfileListResponse.js';
import type { RecordExtensionSampleRequest } from '../models/RecordExtensionSampleRequest.js';
import type { RecordNodePressureRequest } from '../models/RecordNodePressureRequest.js';
import type { RegisterBrowserNodeRequest } from '../models/RegisterBrowserNodeRequest.js';
import type { UpsertExtensionProfileRequest } from '../models/UpsertExtensionProfileRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class CapacityService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List certified Browser Node capacity and live pressure
     * @returns BrowserNodeListResponse Browser Node capacity inventory.
     * @throws ApiError
     */
    public listBrowserNodes(): CancelablePromise<BrowserNodeListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/browser-nodes',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Register or update a certified Browser Node
     * @returns BrowserNode Registered Browser Node.
     * @throws ApiError
     */
    public registerBrowserNode({
        nodeId,
        requestBody,
    }: {
        nodeId: string,
        requestBody: RegisterBrowserNodeRequest,
    }): CancelablePromise<BrowserNode> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/browser-nodes/{nodeId}',
            path: {
                'nodeId': nodeId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Record cgroup/PSI pressure and update admission hysteresis
     * @returns BrowserNode Updated Browser Node pressure.
     * @throws ApiError
     */
    public reportBrowserNodePressure({
        nodeId,
        requestBody,
    }: {
        nodeId: string,
        requestBody: RecordNodePressureRequest,
    }): CancelablePromise<BrowserNode> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/browser-nodes/{nodeId}:pressure',
            path: {
                'nodeId': nodeId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * List Extension Weight profiles
     * @returns ExtensionProfileListResponse Extension profiling inventory.
     * @throws ApiError
     */
    public listExtensionProfiles(): CancelablePromise<ExtensionProfileListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/extensions',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Create or update an Extension Weight profile
     * @returns ExtensionProfile Updated Extension profile.
     * @throws ApiError
     */
    public upsertExtensionProfile({
        extensionId,
        requestBody,
    }: {
        extensionId: string,
        requestBody: UpsertExtensionProfileRequest,
    }): CancelablePromise<ExtensionProfile> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/extensions/{extensionId}',
            path: {
                'extensionId': extensionId,
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
     * Record a budgeted Extension runtime sample and update its P95 profile
     * @returns ExtensionProfile Updated adaptive Extension profile.
     * @throws ApiError
     */
    public recordExtensionProfileSample({
        extensionId,
        requestBody,
    }: {
        extensionId: string,
        requestBody: RecordExtensionSampleRequest,
    }): CancelablePromise<ExtensionProfile> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/extensions/{extensionId}:sample',
            path: {
                'extensionId': extensionId,
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
     * Get the tenant-scoped Browser Placement decision
     * @returns BrowserPlacement Effective resource limits and placement reasons.
     * @throws ApiError
     */
    public getBrowserPlacement({
        sessionId,
    }: {
        sessionId: string,
    }): CancelablePromise<BrowserPlacement> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/browser-placements/{sessionId}',
            path: {
                'sessionId': sessionId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
}
