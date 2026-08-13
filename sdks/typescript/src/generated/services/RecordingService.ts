/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecordingList } from '../models/RecordingList.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class RecordingService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List immutable Session recording manifests
     * Lists the tenant-scoped PostgreSQL projection of Browser Node-authoritative recording manifests. Internal object-storage coordinates and raw recording bytes are not returned.
     *
     * @returns RecordingList Recording manifests ordered newest first.
     * @throws ApiError
     */
    public listSessionRecordings({
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
    }): CancelablePromise<RecordingList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/recordings',
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
}
