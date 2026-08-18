/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AuthorizeHumanAssistRequest } from '../models/AuthorizeHumanAssistRequest.js';
import type { ChallengeAutomationPolicy } from '../models/ChallengeAutomationPolicy.js';
import type { ChallengeAutomationRun } from '../models/ChallengeAutomationRun.js';
import type { ChallengeEvent } from '../models/ChallengeEvent.js';
import type { ChallengeEventListResponse } from '../models/ChallengeEventListResponse.js';
import type { ChallengePreview } from '../models/ChallengePreview.js';
import type { ChallengeVisualJob } from '../models/ChallengeVisualJob.js';
import type { ChallengeVisualJobClaim } from '../models/ChallengeVisualJobClaim.js';
import type { ChallengeVisualJobClaimRequest } from '../models/ChallengeVisualJobClaimRequest.js';
import type { ClaimChallengeVisualJobRequest } from '../models/ClaimChallengeVisualJobRequest.js';
import type { CompleteChallengeVisualJobRequest } from '../models/CompleteChallengeVisualJobRequest.js';
import type { FailChallengeVisualJobRequest } from '../models/FailChallengeVisualJobRequest.js';
import type { HumanAssistIntent } from '../models/HumanAssistIntent.js';
import type { UpdateChallengeAutomationPolicyRequest } from '../models/UpdateChallengeAutomationPolicyRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class HumanAssistService {
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
    /**
     * Get the bounded visual Challenge automation policy
     * @returns ChallengeAutomationPolicy Tenant-scoped policy; new Sessions default to three attempts.
     * @throws ApiError
     */
    public getChallengeAutomationPolicy({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ChallengeAutomationPolicy> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/challenge-automation/policy',
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
     * Update automatic screenshot/OCR click and slide limits
     * High-risk OTP, device, payment and account-security decisions remain manual.
     * @returns ChallengeAutomationPolicy Updated policy.
     * @throws ApiError
     */
    public updateChallengeAutomationPolicy({
        sessionId,
        requestBody,
        xTenantId,
        xActorId,
    }: {
        sessionId: string,
        requestBody: UpdateChallengeAutomationPolicyRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<ChallengeAutomationPolicy> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/sessions/{sessionId}/challenge-automation/policy',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Get the newest durable visual Challenge automation run
     * @returns ChallengeAutomationRun Newest run and attempt status.
     * @throws ApiError
     */
    public getCurrentChallengeAutomationRun({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ChallengeAutomationRun> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/challenge-automation/current',
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
     * Claim one redacted screenshot analysis job with a fenced lease
     * Requires VISION_WORKER; the grant is purpose-bound and one-time.
     * @returns ChallengeVisualJobClaim Claimed job, one-time screenshot URL and Claim Token.
     * @throws ApiError
     */
    public claimChallengeVisualJob({
        requestBody,
    }: {
        requestBody: ClaimChallengeVisualJobRequest,
    }): CancelablePromise<ChallengeVisualJobClaim> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/challenge-visual-jobs:claim',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * ACK start of a claimed visual analysis
     * @returns ChallengeVisualJob Job entered RUNNING.
     * @throws ApiError
     */
    public startChallengeVisualJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: ChallengeVisualJobClaimRequest,
    }): CancelablePromise<ChallengeVisualJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/challenge-visual-jobs/{jobId}:start',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Renew a visual worker fenced lease
     * @returns ChallengeVisualJob Lease renewed.
     * @throws ApiError
     */
    public heartbeatChallengeVisualJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: ChallengeVisualJobClaimRequest,
    }): CancelablePromise<ChallengeVisualJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/challenge-visual-jobs/{jobId}:heartbeat',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Commit a bounded normalized click or slide decision
     * @returns ChallengeVisualJob Decision accepted and either escalated or dispatched to the Browser Node.
     * @throws ApiError
     */
    public completeChallengeVisualJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: CompleteChallengeVisualJobRequest,
    }): CancelablePromise<ChallengeVisualJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/challenge-visual-jobs/{jobId}:complete',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Fail one visual attempt and apply the durable retry budget
     * @returns ChallengeVisualJob Attempt failed; the run was recaptured or escalated.
     * @throws ApiError
     */
    public failChallengeVisualJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: FailChallengeVisualJobRequest,
    }): CancelablePromise<ChallengeVisualJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/challenge-visual-jobs/{jobId}:fail',
            path: {
                'jobId': jobId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
}
