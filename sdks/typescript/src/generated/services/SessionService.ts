/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BrowserPlacement } from '../models/BrowserPlacement.js';
import type { BrowserState } from '../models/BrowserState.js';
import type { BusinessRecoveryValidation } from '../models/BusinessRecoveryValidation.js';
import type { CaptureEvidenceRequest } from '../models/CaptureEvidenceRequest.js';
import type { CommitEnvironmentImportRequest } from '../models/CommitEnvironmentImportRequest.js';
import type { CreateEnvironmentSavedViewRequest } from '../models/CreateEnvironmentSavedViewRequest.js';
import type { CreateEvidenceAccessGrantRequest } from '../models/CreateEvidenceAccessGrantRequest.js';
import type { CreateSafetyLeaseRequest } from '../models/CreateSafetyLeaseRequest.js';
import type { CreateSessionRequest } from '../models/CreateSessionRequest.js';
import type { CreateSessionResponse } from '../models/CreateSessionResponse.js';
import type { EnvironmentImport } from '../models/EnvironmentImport.js';
import type { EnvironmentImportListResponse } from '../models/EnvironmentImportListResponse.js';
import type { EnvironmentSavedView } from '../models/EnvironmentSavedView.js';
import type { EnvironmentSavedViewListResponse } from '../models/EnvironmentSavedViewListResponse.js';
import type { EvidenceAccessGrant } from '../models/EvidenceAccessGrant.js';
import type { EvidenceCapture } from '../models/EvidenceCapture.js';
import type { EvidenceList } from '../models/EvidenceList.js';
import type { OperationResponse } from '../models/OperationResponse.js';
import type { PreviewEnvironmentImportRequest } from '../models/PreviewEnvironmentImportRequest.js';
import type { ProviderEvidence } from '../models/ProviderEvidence.js';
import type { ProviderEvidenceListResponse } from '../models/ProviderEvidenceListResponse.js';
import type { ProxyRebind } from '../models/ProxyRebind.js';
import type { ProxyRebindOperation } from '../models/ProxyRebindOperation.js';
import type { ProxyRebindRequest } from '../models/ProxyRebindRequest.js';
import type { RebindSessionApplicationRequest } from '../models/RebindSessionApplicationRequest.js';
import type { RedeemEvidenceAccessResponse } from '../models/RedeemEvidenceAccessResponse.js';
import type { RemoteDesktopConnection } from '../models/RemoteDesktopConnection.js';
import type { RemoteDesktopParticipant } from '../models/RemoteDesktopParticipant.js';
import type { RemoteDesktopParticipantList } from '../models/RemoteDesktopParticipantList.js';
import type { RenewSafetyLeaseRequest } from '../models/RenewSafetyLeaseRequest.js';
import type { ResourceEventList } from '../models/ResourceEventList.js';
import type { ResourcePolicyOperation } from '../models/ResourcePolicyOperation.js';
import type { ResourcePolicyRequest } from '../models/ResourcePolicyRequest.js';
import type { SafetyLease } from '../models/SafetyLease.js';
import type { SafetyLeaseList } from '../models/SafetyLeaseList.js';
import type { SessionApplicationBinding } from '../models/SessionApplicationBinding.js';
import type { SessionApplicationRebind } from '../models/SessionApplicationRebind.js';
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
     * The ticket is bound to the current Session Context rather than an exclusive HumanTakeover Operation. Connecting keeps an active Agent task alive; Browser Node gives fresh human input priority and resumes deferred Agent input after the human input idle window. Multiple collaborative clients are bounded per Session and use the shared RFB mode. A view-only ticket is enforced by both noVNC and Browser Node; attempted Key, Pointer or Clipboard input is rejected before x11vnc. If the same actor already owns an explicit EXECUTING HumanTakeover, the endpoint preserves that exclusive operation and its release barrier; the Gateway revokes collaborative clients before admitting it.
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
