/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserDownload } from '../models/AgentBrowserDownload.js';
import type { AgentBrowserDownloadList } from '../models/AgentBrowserDownloadList.js';
import type { AgentBrowserFileUpload } from '../models/AgentBrowserFileUpload.js';
import type { AgentBrowserFindRequest } from '../models/AgentBrowserFindRequest.js';
import type { AgentBrowserInspectRequest } from '../models/AgentBrowserInspectRequest.js';
import type { AgentBrowserScreenshot } from '../models/AgentBrowserScreenshot.js';
import type { AgentBrowserSnapshot } from '../models/AgentBrowserSnapshot.js';
import type { AgentBrowserTargetList } from '../models/AgentBrowserTargetList.js';
import type { AgentClipboard } from '../models/AgentClipboard.js';
import type { AgentExecutionJob } from '../models/AgentExecutionJob.js';
import type { AgentExecutionJobClaim } from '../models/AgentExecutionJobClaim.js';
import type { AgentExecutionJobClaimRequest } from '../models/AgentExecutionJobClaimRequest.js';
import type { AgentInputSecret } from '../models/AgentInputSecret.js';
import type { AgentReviewJob } from '../models/AgentReviewJob.js';
import type { AgentReviewJobClaim } from '../models/AgentReviewJobClaim.js';
import type { AgentReviewJobClaimRequest } from '../models/AgentReviewJobClaimRequest.js';
import type { AgentTask } from '../models/AgentTask.js';
import type { AgentTaskListResponse } from '../models/AgentTaskListResponse.js';
import type { AgentTaskSummaryListResponse } from '../models/AgentTaskSummaryListResponse.js';
import type { CaptureAgentBrowserScreenshotRequest } from '../models/CaptureAgentBrowserScreenshotRequest.js';
import type { ClaimAgentExecutionJobRequest } from '../models/ClaimAgentExecutionJobRequest.js';
import type { ClaimAgentReviewJobRequest } from '../models/ClaimAgentReviewJobRequest.js';
import type { CompleteAgentReviewJobRequest } from '../models/CompleteAgentReviewJobRequest.js';
import type { CreateAgentInputSecretRequest } from '../models/CreateAgentInputSecretRequest.js';
import type { CreateAgentTaskRequest } from '../models/CreateAgentTaskRequest.js';
import type { CreateSessionIdentityChangeRequest } from '../models/CreateSessionIdentityChangeRequest.js';
import type { ExecuteAgentBrowserActionsRequest } from '../models/ExecuteAgentBrowserActionsRequest.js';
import type { FailAgentExecutionJobRequest } from '../models/FailAgentExecutionJobRequest.js';
import type { FailAgentReviewJobRequest } from '../models/FailAgentReviewJobRequest.js';
import type { RedeemEvidenceAccessResponse } from '../models/RedeemEvidenceAccessResponse.js';
import type { SessionIdentityChangeRequest } from '../models/SessionIdentityChangeRequest.js';
import type { SessionIdentitySpec } from '../models/SessionIdentitySpec.js';
import type { SessionIdentitySpecInput } from '../models/SessionIdentitySpecInput.js';
import type { UploadAgentBrowserFileRequest } from '../models/UploadAgentBrowserFileRequest.js';
import type { WriteAgentClipboardRequest } from '../models/WriteAgentClipboardRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class AgentService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Get one PostgreSQL-authoritative structured page snapshot
     * Returns DOM/accessibility-derived interactive state. Ordinary pages do not require OCR or screenshots.
     * @returns AgentBrowserSnapshot Structured snapshot and cursor for subsequent inspect or action calls.
     * @throws ApiError
     */
    public getAgentBrowserSnapshot({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserSnapshot> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/snapshot',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Inspect structured elements without another Browser Node round trip
     * @returns AgentBrowserTargetList Requested elements from the exact state cursor.
     * @throws ApiError
     */
    public inspectAgentBrowserElements({
        sessionId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        requestBody: AgentBrowserInspectRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserTargetList> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/inspect',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Find structured elements by semantic name, role, type, stable ID, or visibility reason
     * @returns AgentBrowserTargetList Bounded semantic matches from the authoritative current state.
     * @throws ApiError
     */
    public findAgentBrowserElements({
        sessionId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        requestBody: AgentBrowserFindRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserTargetList> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/find',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Execute one ordered state-fenced Browser action batch
     * Provides the browser.execute_actions fast path. The gateway validates one authoritative state cursor, persists one auditable Agent Task, executes primitives in order, checks state between actions, honors stopOnError, and yields to real VNC input without forcing takeover.
     *
     * @returns AgentTask Persisted task after immediate execution or durable enqueue.
     * @throws ApiError
     */
    public executeAgentBrowserActions({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: ExecuteAgentBrowserActionsRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/execute-actions',
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
                409: `State or idempotency conflict.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Capture one state-fenced, redacted Agent screenshot
     * Captures the exact active Page Target as a viewport, full page, structured element, bounded region, or challenge region. The response contains metadata only. Pixels remain in immutable evidence storage and can be retrieved once through the purpose-bound grant.
     *
     * @returns AgentBrowserScreenshot The durable screenshot command is executing or was idempotently reused.
     * @throws ApiError
     */
    public captureAgentBrowserScreenshot({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CaptureAgentBrowserScreenshotRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserScreenshot> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/screenshots',
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
                409: `State or idempotency conflict.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Read or wait for one PostgreSQL-authoritative screenshot lifecycle
     * Bounded waiting rereads PostgreSQL only; it never polls Chromium or Object Storage.
     * @returns AgentBrowserScreenshot Actor-scoped screenshot metadata without pixels, object paths, or signed URLs.
     * @throws ApiError
     */
    public getAgentBrowserScreenshot({
        sessionId,
        screenshotId,
        xTenantId,
        waitMs,
    }: {
        sessionId: string,
        screenshotId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        waitMs?: number,
    }): CancelablePromise<AgentBrowserScreenshot> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/screenshots/{screenshotId}',
            path: {
                'sessionId': sessionId,
                'screenshotId': screenshotId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'waitMs': waitMs,
            },
            errors: {
                404: `Resource not found.`,
                408: `The screenshot did not become terminal within the bounded wait.`,
                429: `The bounded concurrent stream capacity has been reached.`,
            },
        });
    }
    /**
     * Redeem the screenshot's actor-bound one-time access grant
     * Returns one short-lived exact-object URL and consumes the AGENT_PERCEPTION grant.
     * @returns RedeemEvidenceAccessResponse One-time screenshot access has been redeemed.
     * @throws ApiError
     */
    public redeemAgentBrowserScreenshot({
        sessionId,
        screenshotId,
        xTenantId,
    }: {
        sessionId: string,
        screenshotId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RedeemEvidenceAccessResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/screenshots/{screenshotId}:redeem',
            path: {
                'sessionId': sessionId,
                'screenshotId': screenshotId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
            },
        });
    }
    /**
     * Read only the encrypted AgentClipboard
     * Never reads or aliases the VNC/X11 UserClipboard.
     * @returns AgentClipboard Current AgentClipboard; value is null when empty.
     * @throws ApiError
     */
    public readAgentClipboard({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentClipboard> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/clipboard',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
        });
    }
    /**
     * Replace the isolated AgentClipboard using optimistic concurrency
     * @returns AgentClipboard Updated metadata; plaintext is not reflected by write.
     * @throws ApiError
     */
    public writeAgentClipboard({
        sessionId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        requestBody: WriteAgentClipboardRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentClipboard> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/sessions/{sessionId}/agent-browser/clipboard',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Clear the isolated AgentClipboard using optimistic concurrency
     * @returns AgentClipboard Cleared AgentClipboard metadata.
     * @throws ApiError
     */
    public clearAgentClipboard({
        sessionId,
        expectedVersion,
        xTenantId,
    }: {
        sessionId: string,
        expectedVersion: number,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentClipboard> {
        return this.httpRequest.request({
            method: 'DELETE',
            url: '/api/v1/sessions/{sessionId}/agent-browser/clipboard',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'expectedVersion': expectedVersion,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Stream one bounded file to the Session Node and set an exact file input through CDP
     * File bytes travel only over the authenticated Control Plane to exact Browser Node stream. PostgreSQL stores tenant-scoped lifecycle metadata and the durable Operation; public responses and audit never contain bytes or Node-local paths. No OS file chooser is opened.
     *
     * @returns AgentBrowserFileUpload File is staged and its durable state-fenced Node command is executing.
     * @throws ApiError
     */
    public uploadAgentBrowserFile({
        sessionId,
        idempotencyKey,
        formData,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        formData: UploadAgentBrowserFileRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserFileUpload> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/files/uploads',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            formData: formData,
            mediaType: 'multipart/form-data',
            errors: {
                409: `State or idempotency conflict.`,
                413: `The upload exceeds the configured bounded ingress limit.`,
                422: `The bounded archive was received but failed semantic or integrity validation.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Read the PostgreSQL-authoritative file upload lifecycle
     * @returns AgentBrowserFileUpload Tenant-scoped upload metadata; bytes and local paths are absent.
     * @throws ApiError
     */
    public getAgentBrowserFileUpload({
        sessionId,
        uploadId,
        xTenantId,
    }: {
        sessionId: string,
        uploadId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserFileUpload> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/files/uploads/{uploadId}',
            path: {
                'sessionId': sessionId,
                'uploadId': uploadId,
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
     * List the PostgreSQL-authoritative Browser download lifecycle
     * Download URLs and Node-local paths are never returned.
     * @returns AgentBrowserDownloadList Current bounded download projection and evidence freshness.
     * @throws ApiError
     */
    public listAgentBrowserDownloads({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserDownloadList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/files/downloads',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Wait for one download to reach a terminal authoritative state
     * Bounded waiter rereads PostgreSQL only; it never polls the Browser.
     * @returns AgentBrowserDownload Completed, canceled, or interrupted download state.
     * @throws ApiError
     */
    public waitForAgentBrowserDownload({
        sessionId,
        downloadId,
        xTenantId,
        timeoutMs = 30000,
    }: {
        sessionId: string,
        downloadId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        timeoutMs?: number,
    }): CancelablePromise<AgentBrowserDownload> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/files/downloads/{downloadId}:wait',
            path: {
                'sessionId': sessionId,
                'downloadId': downloadId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'timeoutMs': timeoutMs,
            },
            errors: {
                404: `Resource not found.`,
                408: `Download did not reach a terminal state before the bounded timeout.`,
                409: `State or idempotency conflict.`,
                429: `The bounded concurrent stream capacity has been reached.`,
            },
        });
    }
    /**
     * Read the creation-time locked Browser identity specification
     * @returns SessionIdentitySpec PostgreSQL-authoritative identity spec applied on every Runtime start.
     * @throws ApiError
     */
    public getSessionIdentitySpec({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentitySpec> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/identity-spec',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
        });
    }
    /**
     * Explicitly reject direct post-creation identity mutation
     * @returns void
     * @throws ApiError
     */
    public rejectDirectSessionIdentityMutation({
        sessionId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        requestBody: SessionIdentitySpecInput,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<void> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/sessions/{sessionId}/identity-spec',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Request an approval-backed identity change for a safe restart boundary
     * @returns SessionIdentityChangeRequest Durable pending or idempotently replayed change request.
     * @throws ApiError
     */
    public createSessionIdentityChangeRequest({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateSessionIdentityChangeRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentityChangeRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/identity-change-requests',
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
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Validate and persist a bounded Agent plan
     * External context is data-only. Structured actions bind to the current Target Revision and sensitive target policy.
     * @returns AgentTask A planned or explicitly blocked Agent task.
     * @throws ApiError
     */
    public createAgentTask({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateAgentTaskRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-tasks',
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
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Create an encrypted one-time Agent username, password, or OTP value
     * Available only when the Session is in AUTONOMOUS mode. The plaintext is write-only, tenant/session scoped, expires within 30 minutes, and can be consumed by exactly one TYPE_TEXT task Step. It is never returned to the Agent worker, Vision worker, plan, or API.
     *
     * @returns AgentInputSecret Encrypted one-time input reference created.
     * @throws ApiError
     */
    public createAgentInputSecret({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
        xActorId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateAgentInputSecretRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<AgentInputSecret> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-input-secrets',
            path: {
                'sessionId': sessionId,
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
     * List tenant Agent tasks and plan-security decisions
     * @returns AgentTaskListResponse Tenant-scoped Agent task list.
     * @throws ApiError
     */
    public listAgentTasks({
        xTenantId,
        limit = 20,
        offset,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
        offset?: number,
    }): CancelablePromise<AgentTaskListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-tasks',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
                'offset': offset,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * List lightweight tenant Agent task summaries with stable keyset pagination
     * Returns scalar task state and database-computed counts without transferring plan, execution-result, allowed-domain, or security-event JSON payloads.
     * @returns AgentTaskSummaryListResponse Bounded tenant-scoped Agent task summary page and authoritative aggregate metrics.
     * @throws ApiError
     */
    public listAgentTaskSummaries({
        xTenantId,
        limit = 20,
        cursor,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
        /**
         * Opaque cursor returned by the previous response.
         */
        cursor?: string,
    }): CancelablePromise<AgentTaskSummaryListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-task-summaries',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
                'cursor': cursor,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Get an Agent task and its redacted security evidence
     * @returns AgentTask Tenant-scoped Agent task.
     * @throws ApiError
     */
    public getAgentTask({
        taskId,
        xTenantId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-tasks/{taskId}',
            path: {
                'taskId': taskId,
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
     * Execute and verify a supported Agent plan
     * Executes read, Navigate, Click, Type, Scroll, Wait, and human-handoff steps with durable checkpoints.
     * @returns AgentTask Completed, failed, or previously completed Agent task.
     * @throws ApiError
     */
    public executeAgentTask({
        taskId,
        idempotencyKey,
        xTenantId,
    }: {
        taskId: string,
        idempotencyKey: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:execute',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Claim one opaque Agent execution job with a fenced lease
     * Requires the dedicated AGENT_WORKER role. No prompt, plan, page data, capability token, or customer credential crosses this boundary.
     * @returns AgentExecutionJobClaim Opaque execution job claimed; the single-use Claim Token is returned only once.
     * @throws ApiError
     */
    public claimAgentExecutionJob({
        requestBody,
    }: {
        requestBody: ClaimAgentExecutionJobRequest,
    }): CancelablePromise<AgentExecutionJobClaim> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs:claim',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * ACK execution start for a claimed Agent job
     * @returns AgentExecutionJob Job entered EXECUTING.
     * @throws ApiError
     */
    public startAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentExecutionJobClaimRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:start',
            path: {
                'jobId': jobId,
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
     * Renew an Agent Worker fenced lease
     * @returns AgentExecutionJob Lease renewed.
     * @throws ApiError
     */
    public heartbeatAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentExecutionJobClaimRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:heartbeat',
            path: {
                'jobId': jobId,
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
     * Ask the Control Plane safety kernel to drive an opaque Agent job
     * The Worker cannot invoke Browser Node tools or modify cgroups directly. Capability, Operation, Outbox, revision and human-governance enforcement remain in the Control Plane.
     * @returns AgentExecutionJob Job committed or waiting on an authoritative asynchronous task state.
     * @throws ApiError
     */
    public driveAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentExecutionJobClaimRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:drive',
            path: {
                'jobId': jobId,
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
     * Reject, retry, or permanently fail a claimed Agent job
     * @returns AgentExecutionJob Failure durably projected to the queue and task.
     * @throws ApiError
     */
    public failAgentExecutionJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: FailAgentExecutionJobRequest,
    }): CancelablePromise<AgentExecutionJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-worker-jobs/{jobId}:fail',
            path: {
                'jobId': jobId,
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
     * Claim one capability-free Agent plan review with a fenced lease
     * Requires REVIEWER_WORKER. The payload excludes capability tokens, sealed values, page state, raw context sources and customer credentials.
     * @returns AgentReviewJobClaim A plan review and one-time Claim Token.
     * @throws ApiError
     */
    public claimAgentReviewJob({
        requestBody,
    }: {
        requestBody: ClaimAgentReviewJobRequest,
    }): CancelablePromise<AgentReviewJobClaim> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-review-jobs:claim',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * ACK start of a claimed Agent plan review
     * @returns AgentReviewJob Review entered EXECUTING.
     * @throws ApiError
     */
    public startAgentReviewJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentReviewJobClaimRequest,
    }): CancelablePromise<AgentReviewJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-review-jobs/{jobId}:start',
            path: {
                'jobId': jobId,
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
     * Renew a Reviewer Worker fenced lease
     * @returns AgentReviewJob Lease renewed.
     * @throws ApiError
     */
    public heartbeatAgentReviewJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: AgentReviewJobClaimRequest,
    }): CancelablePromise<AgentReviewJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-review-jobs/{jobId}:heartbeat',
            path: {
                'jobId': jobId,
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
     * Commit a structured model verdict and immutable accounting evidence
     * The Control Plane revalidates the exact plan hash, deployment, model revision, confidence and reason-code policy before execution is released.
     * @returns AgentReviewJob Review approved or rejected; approval atomically releases the execution queue.
     * @throws ApiError
     */
    public completeAgentReviewJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: CompleteAgentReviewJobRequest,
    }): CancelablePromise<AgentReviewJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-review-jobs/{jobId}:complete',
            path: {
                'jobId': jobId,
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
     * Retry or permanently fail a claimed model review
     * @returns AgentReviewJob Failure durably projected to the review queue and Agent task.
     * @throws ApiError
     */
    public failAgentReviewJob({
        jobId,
        requestBody,
    }: {
        jobId: string,
        requestBody: FailAgentReviewJobRequest,
    }): CancelablePromise<AgentReviewJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-review-jobs/{jobId}:fail',
            path: {
                'jobId': jobId,
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
     * Approve a pending high-risk Agent plan
     * @returns AgentTask Approved task, now eligible for execution.
     * @throws ApiError
     */
    public approveAgentTask({
        taskId,
        xTenantId,
        xActorId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:approve',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject a pending high-risk Agent plan
     * @returns AgentTask Rejected task.
     * @throws ApiError
     */
    public rejectAgentTask({
        taskId,
        xTenantId,
        xActorId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:reject',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Accept an Agent human-handoff request
     * @returns AgentTask Handoff accepted and HumanTakeover operation created.
     * @throws ApiError
     */
    public acceptAgentHandoff({
        taskId,
        xTenantId,
        xActorId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:accept-handoff',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject an Agent human-handoff request
     * @returns AgentTask Handoff rejected and Agent task failed closed.
     * @throws ApiError
     */
    public rejectAgentHandoff({
        taskId,
        xTenantId,
        xActorId,
    }: {
        taskId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<AgentTask> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-tasks/{taskId}:reject-handoff',
            path: {
                'taskId': taskId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
}
