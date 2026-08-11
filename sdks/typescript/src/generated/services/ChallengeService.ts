/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AuthorizeHumanAssistRequest } from '../models/AuthorizeHumanAssistRequest.js';
import type { ChallengeEvent } from '../models/ChallengeEvent.js';
import type { ChallengeEventListResponse } from '../models/ChallengeEventListResponse.js';
import type { ChallengePreview } from '../models/ChallengePreview.js';
import type { HumanAssistIntent } from '../models/HumanAssistIntent.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class ChallengeService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List authoritative Challenge Detection events for a Session
     * Detection is input-free and stores only bounded signal codes and hashes.
     * @returns ChallengeEventListResponse Challenge timeline ordered newest first.
     * @throws ApiError
     */
    public listSessionChallenges({
        sessionId,
        xTenantId,
        limit = 20,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
    }): CancelablePromise<ChallengeEventListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/challenges',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Get one tenant-scoped Challenge Event
     * @returns ChallengeEvent Current Challenge Event state.
     * @throws ApiError
     */
    public getChallengeEvent({
        eventId,
        xTenantId,
    }: {
        eventId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ChallengeEvent> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/challenges/{eventId}',
            path: {
                'eventId': eventId,
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
     * Revalidate and preview the exact current Human Assist target
     * The preview hash is actor-, context-, state-, target- and visual-anchor-bound.
     * @returns ChallengePreview Current highlight and authorization gate result.
     * @throws ApiError
     */
    public previewHumanAssist({
        eventId,
        xTenantId,
        xActorId,
    }: {
        eventId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<ChallengePreview> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/challenges/{eventId}/preview',
            path: {
                'eventId': eventId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Authorize and consume exactly one Human Assist click
     * Creates a HUMAN_ASSIST Operation only after revalidating the current preview. The action budget is exactly one and an execution failure is never retried automatically.
     *
     * @returns HumanAssistIntent Single-use intent consumed and Node Operation accepted.
     * @throws ApiError
     */
    public authorizeHumanAssist({
        eventId,
        idempotencyKey,
        requestBody,
        xTenantId,
        xActorId,
    }: {
        eventId: string,
        idempotencyKey: string,
        requestBody: AuthorizeHumanAssistRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<HumanAssistIntent> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/challenges/{eventId}/assist-authorizations',
            path: {
                'eventId': eventId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
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
}
