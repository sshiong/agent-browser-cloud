/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { UpdateUserPreferencesRequest } from '../models/UpdateUserPreferencesRequest.js';
import type { UserPreferences } from '../models/UserPreferences.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class UserPreferenceService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Get the authenticated actor's durable UI preferences
     * Returns tenant-and-actor scoped preferences shared by Web and Tauri clients. Actors without a stored row resolve to SYSTEM without requiring a database backfill.
     *
     * @returns UserPreferences Current actor preference or the system default.
     * @throws ApiError
     */
    public getUserPreferences(): CancelablePromise<UserPreferences> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/user-preferences',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Persist the authenticated actor's UI preferences
     * The tenant and actor are always derived from the authenticated identity. Repeating the same theme mode is idempotent and does not advance the persisted version.
     *
     * @returns UserPreferences Persisted actor preference.
     * @throws ApiError
     */
    public updateUserPreferences({
        requestBody,
    }: {
        requestBody: UpdateUserPreferencesRequest,
    }): CancelablePromise<UserPreferences> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/user-preferences',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
}
