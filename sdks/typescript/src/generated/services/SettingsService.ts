/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { WorkspaceSettings } from '../models/WorkspaceSettings.js';
import type { WorkspaceSettingsRequest } from '../models/WorkspaceSettingsRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class SettingsService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Read effective tenant Workspace defaults
     * Returns a PostgreSQL Workspace override when one exists, otherwise the declared Control Plane system default with source=SYSTEM_DEFAULT.
     *
     * @returns WorkspaceSettings Effective Workspace Settings.
     * @throws ApiError
     */
    public getWorkspaceSettings(): CancelablePromise<WorkspaceSettings> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/workspace-settings',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Persist audited defaults for future Sessions
     * Updates affect only future Session creation. Runtime, region and HumanTakeover values are bound at creation and existing Sessions are not rewritten.
     *
     * @returns WorkspaceSettings Persisted Workspace Settings.
     * @throws ApiError
     */
    public updateWorkspaceSettings({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: WorkspaceSettingsRequest,
    }): CancelablePromise<WorkspaceSettings> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/workspace-settings',
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
}
