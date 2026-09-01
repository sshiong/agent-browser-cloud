/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserDownload } from '../models/AgentBrowserDownload.js';
import type { AgentBrowserDownloadList } from '../models/AgentBrowserDownloadList.js';
import type { AgentBrowserEvaluation } from '../models/AgentBrowserEvaluation.js';
import type { AgentBrowserFileUpload } from '../models/AgentBrowserFileUpload.js';
import type { AgentBrowserFindRequest } from '../models/AgentBrowserFindRequest.js';
import type { AgentBrowserInspectRequest } from '../models/AgentBrowserInspectRequest.js';
import type { AgentBrowserScreenshot } from '../models/AgentBrowserScreenshot.js';
import type { AgentBrowserSnapshot } from '../models/AgentBrowserSnapshot.js';
import type { AgentBrowserTargetList } from '../models/AgentBrowserTargetList.js';
import type { AgentClipboard } from '../models/AgentClipboard.js';
import type { AgentClipboardBridge } from '../models/AgentClipboardBridge.js';
import type { AgentTask } from '../models/AgentTask.js';
import type { BrowserPlacement } from '../models/BrowserPlacement.js';
import type { BrowserState } from '../models/BrowserState.js';
import type { BusinessRecoveryValidation } from '../models/BusinessRecoveryValidation.js';
import type { CaptureAgentBrowserScreenshotRequest } from '../models/CaptureAgentBrowserScreenshotRequest.js';
import type { CaptureEvidenceRequest } from '../models/CaptureEvidenceRequest.js';
import type { CommitEnvironmentImportRequest } from '../models/CommitEnvironmentImportRequest.js';
import type { CompleteAgentClipboardBridgeRequest } from '../models/CompleteAgentClipboardBridgeRequest.js';
import type { CreateAgentBrowserEvaluationRequest } from '../models/CreateAgentBrowserEvaluationRequest.js';
import type { CreateAgentClipboardBridgeRequest } from '../models/CreateAgentClipboardBridgeRequest.js';
import type { CreateEnvironmentSavedViewRequest } from '../models/CreateEnvironmentSavedViewRequest.js';
import type { CreateEvidenceAccessGrantRequest } from '../models/CreateEvidenceAccessGrantRequest.js';
import type { CreateSafetyLeaseRequest } from '../models/CreateSafetyLeaseRequest.js';
import type { CreateSessionIdentityChangeRequest } from '../models/CreateSessionIdentityChangeRequest.js';
import type { CreateSessionRequest } from '../models/CreateSessionRequest.js';
import type { CreateSessionResponse } from '../models/CreateSessionResponse.js';
import type { EnvironmentImport } from '../models/EnvironmentImport.js';
import type { EnvironmentImportListResponse } from '../models/EnvironmentImportListResponse.js';
import type { EnvironmentSavedView } from '../models/EnvironmentSavedView.js';
import type { EnvironmentSavedViewListResponse } from '../models/EnvironmentSavedViewListResponse.js';
import type { EvidenceAccessGrant } from '../models/EvidenceAccessGrant.js';
import type { EvidenceCapture } from '../models/EvidenceCapture.js';
import type { EvidenceList } from '../models/EvidenceList.js';
import type { ExecuteAgentBrowserActionsRequest } from '../models/ExecuteAgentBrowserActionsRequest.js';
import type { OperationResponse } from '../models/OperationResponse.js';
import type { PreviewEnvironmentImportRequest } from '../models/PreviewEnvironmentImportRequest.js';
import type { ProviderEvidence } from '../models/ProviderEvidence.js';
import type { ProviderEvidenceListResponse } from '../models/ProviderEvidenceListResponse.js';
import type { ProxyRebind } from '../models/ProxyRebind.js';
import type { ProxyRebindOperation } from '../models/ProxyRebindOperation.js';
import type { ProxyRebindRequest } from '../models/ProxyRebindRequest.js';
import type { RebindSessionApplicationRequest } from '../models/RebindSessionApplicationRequest.js';
import type { RecordingList } from '../models/RecordingList.js';
import type { RedeemEvidenceAccessResponse } from '../models/RedeemEvidenceAccessResponse.js';
import type { RemoteDesktopConnection } from '../models/RemoteDesktopConnection.js';
import type { RemoteDesktopParticipant } from '../models/RemoteDesktopParticipant.js';
import type { RemoteDesktopParticipantHistoryPage } from '../models/RemoteDesktopParticipantHistoryPage.js';
import type { RemoteDesktopParticipantList } from '../models/RemoteDesktopParticipantList.js';
import type { RenewSafetyLeaseRequest } from '../models/RenewSafetyLeaseRequest.js';
import type { ResourceEventList } from '../models/ResourceEventList.js';
import type { ResourcePolicyOperation } from '../models/ResourcePolicyOperation.js';
import type { ResourcePolicyRequest } from '../models/ResourcePolicyRequest.js';
import type { SafetyLease } from '../models/SafetyLease.js';
import type { SafetyLeaseList } from '../models/SafetyLeaseList.js';
import type { SessionApplicationBinding } from '../models/SessionApplicationBinding.js';
import type { SessionApplicationRebind } from '../models/SessionApplicationRebind.js';
import type { SessionIdentityChangeRequest } from '../models/SessionIdentityChangeRequest.js';
import type { SessionIdentitySpec } from '../models/SessionIdentitySpec.js';
import type { SessionIdentitySpecInput } from '../models/SessionIdentitySpecInput.js';
import type { SessionListResponse } from '../models/SessionListResponse.js';
import type { SessionMigration } from '../models/SessionMigration.js';
import type { SessionResource } from '../models/SessionResource.js';
import type { SessionSafePoint } from '../models/SessionSafePoint.js';
import type { SessionState } from '../models/SessionState.js';
import type { SessionView } from '../models/SessionView.js';
import type { StateResyncRequest } from '../models/StateResyncRequest.js';
import type { StateResyncResponse } from '../models/StateResyncResponse.js';
import type { SubmitProviderEvidenceRequest } from '../models/SubmitProviderEvidenceRequest.js';
import type { UpdateEnvironmentSavedViewRequest } from '../models/UpdateEnvironmentSavedViewRequest.js';
import type { UploadAgentBrowserFileRequest } from '../models/UploadAgentBrowserFileRequest.js';
import type { WriteAgentClipboardRequest } from '../models/WriteAgentClipboardRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class SessionService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Create a Session
     * @returns CreateSessionResponse Session created, or the original idempotent result.
     * @throws ApiError
     */
    public createSession({
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        idempotencyKey: string,
        requestBody: CreateSessionRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<CreateSessionResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions',
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List tenant Sessions
     * @returns SessionListResponse Tenant-scoped Session list.
     * @throws ApiError
     */
    public listSessions({
        xTenantId,
        state,
        q,
        groupId,
        tagId,
        tagMatch = 'ANY',
        limit = 20,
        offset,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        state?: SessionState,
        /**
         * Tenant-scoped search across Session ID, display name, Profile, region, resource class, and governed metadata.
         */
        q?: string,
        /**
         * Restrict results to one tenant-owned Workspace Group.
         */
        groupId?: string,
        /**
         * Repeat to filter by multiple tenant-owned Workspace Tags.
         */
        tagId?: Array<string>,
        /**
         * Match any selected Tag or require all selected Tags.
         */
        tagMatch?: 'ANY' | 'ALL',
        limit?: number,
        offset?: number,
    }): CancelablePromise<SessionListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'state': state,
                'q': q,
                'groupId': groupId,
                'tagId': tagId,
                'tagMatch': tagMatch,
                'limit': limit,
                'offset': offset,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Get Session details
     * @returns SessionView Session detail.
     * @throws ApiError
     */
    public getSession({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionView> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Get the latest Browser Current State
     * @returns BrowserState Latest tenant-scoped Browser State.
     * @throws ApiError
     */
    public getBrowserState({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<BrowserState> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/state',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
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
     * Execute one governed, state-fenced JavaScript evaluation
     * Runs bounded JavaScript against the exact active Page Target. READ_ONLY asks Chromium to reject side effects; PAGE_ACTION may mutate the page after intent policy checks. Script source is sealed for Node delivery and is never returned or copied to ordinary audit rows. Cookie, credential, storage, clipboard, network, navigation and tab escape APIs are rejected.
     *
     * @returns AgentBrowserEvaluation The exclusive PostgreSQL-authoritative evaluation is executing or reused.
     * @throws ApiError
     */
    public createAgentBrowserEvaluation({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateAgentBrowserEvaluationRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentBrowserEvaluation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/evaluations',
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
     * Read or wait for one governed JavaScript evaluation
     * Bounded waiting rereads PostgreSQL only; it never polls Chromium.
     * @returns AgentBrowserEvaluation Actor-scoped bounded result; script source is never returned.
     * @throws ApiError
     */
    public getAgentBrowserEvaluation({
        sessionId,
        evaluationId,
        xTenantId,
        waitMs,
    }: {
        sessionId: string,
        evaluationId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        waitMs?: number,
    }): CancelablePromise<AgentBrowserEvaluation> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/agent-browser/evaluations/{evaluationId}',
            path: {
                'sessionId': sessionId,
                'evaluationId': evaluationId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'waitMs': waitMs,
            },
            errors: {
                404: `Resource not found.`,
                408: `The evaluation did not become terminal within the bounded wait.`,
                429: `The bounded concurrent stream capacity has been reached.`,
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
        includeValue = true,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Set false for metadata-only reads that never decrypt plaintext.
         */
        includeValue?: boolean,
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
            query: {
                'includeValue': includeValue,
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
     * Explicitly bridge one current RFB UserClipboard and the isolated AgentClipboard
     * Client-mediated, actor/connection/purpose-bound transfer. USER_TO_AGENT requires a fresh RFB ServerCutText observation. AGENT_TO_USER returns one short-lived value for injection through the same non-view-only noVNC connection and must then be completed. Passwords and OTPs remain on the purpose-bound one-time sensitive-input API. Plaintext is never audited.
     *
     * @returns AgentClipboardBridge Transfer completed or an actor-bound AGENT_TO_USER delivery was issued.
     * @throws ApiError
     */
    public createAgentClipboardBridge({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateAgentClipboardBridgeRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentClipboardBridge> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/clipboard-bridges',
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
     * Confirm that one issued Agent-to-User bridge was injected into RFB/X11
     * @returns AgentClipboardBridge Delivery was acknowledged; the bridge ledger contains no content material.
     * @throws ApiError
     */
    public completeAgentClipboardBridge({
        sessionId,
        bridgeId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        bridgeId: string,
        requestBody: CompleteAgentClipboardBridgeRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentClipboardBridge> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/agent-browser/clipboard-bridges/{bridgeId}:complete',
            path: {
                'sessionId': sessionId,
                'bridgeId': bridgeId,
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
     * Approve a tenant Session identity change
     * @returns SessionIdentityChangeRequest Decided change request.
     * @throws ApiError
     */
    public approveSessionIdentityChangeRequest({
        requestId,
        xTenantId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentityChangeRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/session-identity-change-requests/{requestId}:approve',
            path: {
                'requestId': requestId,
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
     * Reject a tenant Session identity change
     * @returns SessionIdentityChangeRequest Decided change request.
     * @throws ApiError
     */
    public rejectSessionIdentityChangeRequest({
        requestId,
        xTenantId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentityChangeRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/session-identity-change-requests/{requestId}:reject',
            path: {
                'requestId': requestId,
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
     * Apply an approved identity change while the Runtime is safely stopped
     * @returns SessionIdentityChangeRequest Applied change request.
     * @throws ApiError
     */
    public applySessionIdentityChangeRequest({
        requestId,
        xTenantId,
    }: {
        requestId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionIdentityChangeRequest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/session-identity-change-requests/{requestId}:apply',
            path: {
                'requestId': requestId,
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
     * Get the authoritative Session resource policy, allocation and real telemetry
     * @returns SessionResource Resource state. Usage is null until Browser Node telemetry exists.
     * @throws ApiError
     */
    public getSessionResources({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionResource> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/resources',
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
     * List the PostgreSQL-backed resource adjustment timeline
     * @returns ResourceEventList Resource policy and adjustment events.
     * @throws ApiError
     */
    public listSessionResourceEvents({
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
    }): CancelablePromise<ResourceEventList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/resource-events',
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
    /**
     * List tenant-scoped real screenshot evidence metadata
     * Lists the durable metadata index for real CDP screenshots committed through the privileged Storage Helper. Raw pixels and internal object-storage keys are not returned.
     *
     * @returns EvidenceList Screenshot evidence metadata ordered newest first.
     * @throws ApiError
     */
    public listSessionEvidence({
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
    }): CancelablePromise<EvidenceList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/evidence',
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
    /**
     * Request an administrator-governed real Observer screenshot
     * Requires a tenant/security/platform administrator. The request is durable and idempotent; EXECUTING becomes COMMITTED or FAILED only after the Browser Node submits real evidence or the command is dead-lettered. Capture is rejected during HumanTakeover. No screenshot bytes cross this API.
     *
     * @returns EvidenceCapture Durable capture request accepted.
     * @throws ApiError
     */
    public captureSessionEvidence({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CaptureEvidenceRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<EvidenceCapture> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/evidence:capture',
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
     * Read the durable Observer capture request state
     * @returns EvidenceCapture Current capture request state.
     * @throws ApiError
     */
    public getSessionEvidenceCapture({
        sessionId,
        captureId,
        xTenantId,
    }: {
        sessionId: string,
        captureId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<EvidenceCapture> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/evidence-captures/{captureId}',
            path: {
                'sessionId': sessionId,
                'captureId': captureId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Create a purpose-bound one-time evidence access grant
     * Requires a tenant/security/platform administrator. The grant is bound to the authenticated actor, Session, immutable evidence object and declared purpose, and expires within five minutes. It does not contain storage coordinates or a signed URL.
     *
     * @returns EvidenceAccessGrant One-time access grant issued.
     * @throws ApiError
     */
    public createSessionEvidenceAccessGrant({
        sessionId,
        idempotencyKey,
        evidenceId,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        evidenceId: string,
        requestBody: CreateEvidenceAccessGrantRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<EvidenceAccessGrant> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/evidence/{evidenceId}/access-grants',
            path: {
                'sessionId': sessionId,
                'evidenceId': evidenceId,
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
     * Redeem an evidence access grant exactly once
     * Only the actor who created the unexpired grant may redeem it. The Browser Node signs one exact immutable screenshot object for 60 seconds over internal mTLS. The returned URL is ephemeral and is never persisted or written to audit logs.
     *
     * @returns RedeemEvidenceAccessResponse Ephemeral exact-object access.
     * @throws ApiError
     */
    public redeemSessionEvidenceAccessGrant({
        sessionId,
        grantId,
        xTenantId,
    }: {
        sessionId: string,
        grantId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RedeemEvidenceAccessResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/evidence-access-grants/{grantId}:redeem',
            path: {
                'sessionId': sessionId,
                'grantId': grantId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Stream durable Session resource changes with resumable SSE
     * Emits PostgreSQL-backed resource sample, adjustment and application safety lease change notifications. Reconnect with Last-Event-ID to replay changes after the last processed monotonic sequence. The event data identifies the durable row; clients then refresh the authoritative resource, timeline, safe-point and migration views.
     *
     * @returns string SSE stream. Events are resource-stream-ready, resource-stream-reset and session-resource-change; keepalive comments do not advance the cursor.
     *
     * @throws ApiError
     */
    public streamSessionResourceChanges({
        sessionId,
        xTenantId,
        lastEventId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Last successfully processed numeric resource stream sequence.
         */
        lastEventId?: string,
    }): CancelablePromise<string> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/resource-stream',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Last-Event-ID': lastEventId,
            },
            errors: {
                400: `Invalid request.`,
                404: `Resource not found.`,
                429: `Per-process or per-Session live subscriber bound reached.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Stream the canonical durable Session change feed with resumable SSE
     * Emits payload-free invalidation envelopes for Session lifecycle, Browser Current State, audit, Operation, Agent task, resource and application safety lease changes. Reconnect with Last-Event-ID to replay committed envelopes after the last processed per-Session monotonic sequence. Clients refresh the corresponding authoritative REST view; the stream never duplicates Browser state or audit details.
     *
     * @returns string SSE stream. Events are session-stream-ready, session-stream-reset and session-change; keepalive comments do not advance the cursor.
     *
     * @throws ApiError
     */
    public streamSessionChanges({
        sessionId,
        xTenantId,
        lastEventId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Last successfully processed numeric Session stream sequence.
         */
        lastEventId?: string,
    }): CancelablePromise<string> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/event-stream',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Last-Event-ID': lastEventId,
            },
            errors: {
                400: `Invalid request.`,
                404: `Resource not found.`,
                429: `Per-process or per-Session live subscriber bound reached.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Assess whether a Session is at a migration-safe point
     * Fail-closed aggregation of fresh Browser Node input observations and durable control-plane blockers.
     * @returns SessionSafePoint Tenant-scoped safe-point assessment.
     * @throws ApiError
     */
    public getSessionSafePoint({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionSafePoint> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/safe-point',
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
     * Acquire an application business-activity Safe Point lease
     * Application adapters acquire a short owner-bound lease before file transfer, SPA/form submission, payment/account-security work, critical transactions or while business recovery is unknown. Active leases block migration and hibernation. The APPLICATION_ADAPTER role is accepted only on acquire/renew/release signal endpoints; it does not grant general Session operation authority.
     *
     * @returns SafetyLease Lease acquired or an idempotent acquisition replayed.
     * @throws ApiError
     */
    public acquireSessionSafetyLease({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: CreateSafetyLeaseRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SafetyLease> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/safety-leases',
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
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List the latest durable application Safe Point leases for a Session
     * @returns SafetyLeaseList Bounded latest current and terminal lease records plus the authoritative total.
     * @throws ApiError
     */
    public listSessionSafetyLeases({
        sessionId,
        xTenantId,
        limit = 50,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
    }): CancelablePromise<SafetyLeaseList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/safety-leases',
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
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Renew an owned active application Safe Point lease
     * @returns SafetyLease Lease renewed or an idempotent renewal replayed.
     * @throws ApiError
     */
    public renewSessionSafetyLease({
        sessionId,
        leaseId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        leaseId: string,
        idempotencyKey: string,
        requestBody: RenewSafetyLeaseRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SafetyLease> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/sessions/{sessionId}/safety-leases/{leaseId}',
            path: {
                'sessionId': sessionId,
                'leaseId': leaseId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Release an owned application Safe Point lease
     * @returns SafetyLease Terminal lease state.
     * @throws ApiError
     */
    public releaseSessionSafetyLease({
        sessionId,
        leaseId,
        idempotencyKey,
        xTenantId,
    }: {
        sessionId: string,
        leaseId: string,
        idempotencyKey: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SafetyLease> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/safety-leases/{leaseId}:release',
            path: {
                'sessionId': sessionId,
                'leaseId': leaseId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Get the latest durable cross-node migration workflow
     * @returns SessionMigration Latest migration phase and recovery result.
     * @throws ApiError
     */
    public getLatestSessionMigration({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionMigration> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/migration',
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
     * Get the latest durable Business Recovery verdict
     * @returns BusinessRecoveryValidation Latest tenant-scoped Business Recovery validation.
     * @throws ApiError
     */
    public getBusinessRecoveryValidation({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<BusinessRecoveryValidation> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/business-recovery',
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
     * Evaluate current authoritative Browser State against the bound Application contract
     * Persists an idempotent verdict. Tenant JavaScript and regular expressions are not executed; only the bounded declarative recovery contract is evaluated.
     *
     * @returns BusinessRecoveryValidation Durable Business Recovery verdict.
     * @throws ApiError
     */
    public validateBusinessRecovery({
        sessionId,
        idempotencyKey,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<BusinessRecoveryValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/business-recovery:validate',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List recent trusted Provider evidence for a Session
     * @returns ProviderEvidenceListResponse Tenant-scoped Provider evidence with raw Provider references removed.
     * @throws ApiError
     */
    public listBusinessRecoveryProviderEvidence({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProviderEvidenceListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/business-recovery/provider-evidence',
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
     * Submit a short-lived Application Adapter attestation
     * Requires the APPLICATION_ADAPTER role. Evidence is accepted only for a requirement in the exact approved contract revision and the current Context Epoch and Browser State version. Raw Provider references are hashed before persistence.
     *
     * @returns ProviderEvidence Durable Provider evidence accepted for the exact Session state.
     * @throws ApiError
     */
    public submitBusinessRecoveryProviderEvidence({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: SubmitProviderEvidenceRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ProviderEvidence> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/business-recovery/provider-evidence',
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
     * Read the exact Application Recovery Contract revision bound to a Session
     * @returns SessionApplicationBinding Current binding and approved head upgrade availability.
     * @throws ApiError
     */
    public getSessionApplicationBinding({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionApplicationBinding> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/application-binding',
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
     * Explicitly upgrade a Session to the current approved contract revision
     * Administrative, idempotent, tenant-scoped Operation. The Session row and binding are locked, active exclusive Operations block the change, and historical policy is never mutated in place.
     *
     * @returns SessionApplicationRebind Application binding Operation committed.
     * @throws ApiError
     */
    public rebindSessionApplicationContract({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: RebindSessionApplicationRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionApplicationRebind> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/application-binding:rebind',
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
     * Update AUTO resource policy through an idempotent backend Operation
     * @returns ResourcePolicyOperation Resource policy Operation committed.
     * @throws ApiError
     */
    public updateSessionResourcePolicy({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: ResourcePolicyRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ResourcePolicyOperation> {
        return this.httpRequest.request({
            method: 'PATCH',
            url: '/api/v1/sessions/{sessionId}/resource-policy',
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
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Start a Session Runtime
     * @returns OperationResponse Start operation accepted.
     * @throws ApiError
     */
    public startSession({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:start',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Request a Full or Region Browser State Resync
     * @returns StateResyncResponse State Resync command was queued.
     * @throws ApiError
     */
    public resyncBrowserState({
        sessionId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        requestBody: StateResyncRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<StateResyncResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:resync-state',
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
                429: `The bounded concurrent stream capacity has been reached.`,
            },
        });
    }
    /**
     * Terminate a Session
     * @returns OperationResponse Termination operation accepted.
     * @throws ApiError
     */
    public terminateSession({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:terminate',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Acquire exclusive HumanTakeover control
     * @returns OperationResponse HumanTakeover input barrier accepted.
     * @throws ApiError
     */
    public requestHumanTakeover({
        sessionId,
        xTenantId,
        xActorId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:takeover',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Release HumanTakeover after input cleanup and state resync
     * @returns OperationResponse HumanTakeover release barrier accepted.
     * @throws ApiError
     */
    public releaseHumanTakeover({
        sessionId,
        xTenantId,
        xActorId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:release-takeover',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Issue a collaborative noVNC ticket without preempting the Agent
     * The ticket is bound to the current Session Context rather than an exclusive HumanTakeover Operation. Connecting keeps an active Agent task alive; Browser Node gives fresh human input priority and resumes deferred Agent input after the human input idle window. Multiple collaborative clients are bounded per Session and use the shared RFB mode. A view-only ticket is enforced by both noVNC and Browser Node; attempted Key, Pointer or Clipboard input is rejected before x11vnc. An existing Agent or human governance Operation never changes this connection into exclusive mode. Legacy signed EXCLUSIVE_TAKEOVER tickets are normalized to collaborative admission by Browser Node and cannot revoke another Viewer or the Agent workflow. The Control Plane also signs an actor-specific bandwidth and forwarding-frequency ceiling into every ticket. Browser Node shares that budget across the same actor's concurrent windows, isolates different actors, and never throttles the independent human/Agent input path.
     * @returns RemoteDesktopConnection Session-bound collaborative connection ticket issued.
     * @throws ApiError
     */
    public createRemoteDesktopConnection({
        sessionId,
        xTenantId,
        xActorId,
        viewOnly = false,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
        /**
         * Request a server-enforced observation-only connection.
         */
        viewOnly?: boolean,
    }): CancelablePromise<RemoteDesktopConnection> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}:desktop-connection',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
            },
            query: {
                'viewOnly': viewOnly,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List Browser Node-confirmed online remote desktop participants
     * @returns RemoteDesktopParticipantList Current participant projection sourced from real gateway lifecycle events.
     * @throws ApiError
     */
    public listRemoteDesktopParticipants({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RemoteDesktopParticipantList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/desktop-participants',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * List retained terminal remote desktop participant history
     * Returns a stable keyset page of revoked and disconnected connections. Online and revoke-requested participants remain available from the online endpoint and are never removed by terminal-history retention cleanup.
     * @returns RemoteDesktopParticipantHistoryPage Session-bound retained participant history page.
     * @throws ApiError
     */
    public listRemoteDesktopParticipantHistory({
        sessionId,
        xTenantId,
        limit = 20,
        cursor,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        limit?: number,
        cursor?: string,
    }): CancelablePromise<RemoteDesktopParticipantHistoryPage> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/sessions/{sessionId}/desktop-participants/history',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'limit': limit,
                'cursor': cursor,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Revoke exactly one remote desktop connection without stopping Agent or Browser
     * @returns RemoteDesktopParticipant Exact connection revocation was durably queued for its Browser Node.
     * @throws ApiError
     */
    public revokeRemoteDesktopParticipant({
        sessionId,
        idempotencyKey,
        connectionId,
        xTenantId,
        xActorId,
    }: {
        sessionId: string,
        idempotencyKey: string,
        connectionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        /**
         * Optional Local/Test actor identity. Ignored in Production, where actor identity is the JWT subject.
         */
        xActorId?: string,
    }): CancelablePromise<RemoteDesktopParticipant> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/sessions/{sessionId}/desktop-participants/{connectionId}:revoke',
            path: {
                'sessionId': sessionId,
                'connectionId': connectionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
                'X-Actor-Id': xActorId,
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
    /**
     * List the current operator's tenant-authoritative import jobs
     * @returns EnvironmentImportListResponse The latest import jobs owned by the authenticated actor.
     * @throws ApiError
     */
    public listEnvironmentImports(): CancelablePromise<EnvironmentImportListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/environment-imports',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Validate an Environment manifest without creating runtime resources
     * @returns EnvironmentImport PostgreSQL-authoritative validation result.
     * @throws ApiError
     */
    public previewEnvironmentImport({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: PreviewEnvironmentImportRequest,
    }): CancelablePromise<EnvironmentImport> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/environment-imports:preview',
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
     * Read one actor-owned Environment Import ledger
     * @returns EnvironmentImport Import validation and execution ledger.
     * @throws ApiError
     */
    public getEnvironmentImport({
        importId,
    }: {
        importId: string,
    }): CancelablePromise<EnvironmentImport> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/environment-imports/{importId}',
            path: {
                'importId': importId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Create every validated Session and Operation in one transaction
     * A failed item rolls back the entire import; partial success is never published.
     * @returns EnvironmentImport Committed import with real Session and Operation identifiers.
     * @throws ApiError
     */
    public commitEnvironmentImport({
        importId,
        idempotencyKey,
        requestBody,
    }: {
        importId: string,
        idempotencyKey: string,
        requestBody: CommitEnvironmentImportRequest,
    }): CancelablePromise<EnvironmentImport> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/environment-imports/{importId}:commit',
            path: {
                'importId': importId,
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
}
