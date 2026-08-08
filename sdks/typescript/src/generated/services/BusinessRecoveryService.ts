/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BusinessRecoveryValidation } from '../models/BusinessRecoveryValidation.js';
import type { ProviderEvidence } from '../models/ProviderEvidence.js';
import type { ProviderEvidenceListResponse } from '../models/ProviderEvidenceListResponse.js';
import type { RebindSessionApplicationRequest } from '../models/RebindSessionApplicationRequest.js';
import type { RecoveryContract } from '../models/RecoveryContract.js';
import type { RecoveryContractApproval } from '../models/RecoveryContractApproval.js';
import type { RecoveryContractDiff } from '../models/RecoveryContractDiff.js';
import type { RecoveryContractListResponse } from '../models/RecoveryContractListResponse.js';
import type { RecoveryContractRevisionListResponse } from '../models/RecoveryContractRevisionListResponse.js';
import type { RequestRecoveryContractApprovalRequest } from '../models/RequestRecoveryContractApprovalRequest.js';
import type { RestoreRecoveryContractRevisionRequest } from '../models/RestoreRecoveryContractRevisionRequest.js';
import type { SessionApplicationBinding } from '../models/SessionApplicationBinding.js';
import type { SessionApplicationRebind } from '../models/SessionApplicationRebind.js';
import type { SubmitProviderEvidenceRequest } from '../models/SubmitProviderEvidenceRequest.js';
import type { UpsertRecoveryContractRequest } from '../models/UpsertRecoveryContractRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class BusinessRecoveryService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
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
     * List tenant Application Recovery Contracts
     * @returns RecoveryContractListResponse Tenant-scoped contracts.
     * @throws ApiError
     */
    public listApplicationRecoveryContracts({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContractListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/applications/recovery-contracts',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Get a tenant Application Recovery Contract
     * @returns RecoveryContract Application Recovery Contract.
     * @throws ApiError
     */
    public getApplicationRecoveryContract({
        applicationId,
        xTenantId,
    }: {
        applicationId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContract> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/applications/{applicationId}/recovery-contract',
            path: {
                'applicationId': applicationId,
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
     * Create or update a versioned declarative Application Recovery Contract
     * @returns RecoveryContract Current contract.
     * @throws ApiError
     */
    public upsertApplicationRecoveryContract({
        applicationId,
        requestBody,
        xTenantId,
    }: {
        applicationId: string,
        requestBody: UpsertRecoveryContractRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContract> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/applications/{applicationId}/recovery-contract',
            path: {
                'applicationId': applicationId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
                422: `Invalid request.`,
            },
        });
    }
    /**
     * List immutable snapshots for an Application Recovery Contract
     * Returns exact append-only policy bodies in descending version order with the approval decision attached to each exact version.
     *
     * @returns RecoveryContractRevisionListResponse Immutable contract snapshots.
     * @throws ApiError
     */
    public listApplicationRecoveryContractRevisions({
        applicationId,
        xTenantId,
    }: {
        applicationId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContractRevisionListResponse> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/applications/{applicationId}/recovery-contract/revisions',
            path: {
                'applicationId': applicationId,
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
     * Compare two immutable Application Recovery Contract versions
     * @returns RecoveryContractDiff Field-level exact-version comparison.
     * @throws ApiError
     */
    public diffApplicationRecoveryContractRevisions({
        applicationId,
        version,
        compareToVersion,
        xTenantId,
    }: {
        applicationId: string,
        version: number,
        compareToVersion: number,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContractDiff> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/applications/{applicationId}/recovery-contract/revisions/{version}/diff',
            path: {
                'applicationId': applicationId,
                'version': version,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'compareToVersion': compareToVersion,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Restore approved history as a new draft contract version
     * The selected historical body is copied into a new immutable head version. History and existing Session bindings are never mutated. The new version is DRAFT and must pass the normal dual-control approval gate.
     *
     * @returns RecoveryContract Newly created draft head version.
     * @throws ApiError
     */
    public restoreApplicationRecoveryContractRevision({
        applicationId,
        idempotencyKey,
        requestBody,
        xTenantId,
    }: {
        applicationId: string,
        idempotencyKey: string,
        requestBody: RestoreRecoveryContractRevisionRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContract> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/applications/{applicationId}/recovery-contract:restore',
            path: {
                'applicationId': applicationId,
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
                422: `Invalid request.`,
            },
        });
    }
    /**
     * Request dual-control approval for the exact current contract version
     * Replays return the existing pending or approved decision for the same version. Editing the contract creates a new version and invalidates the prior approval.
     *
     * @returns RecoveryContractApproval Current approval request for the exact contract version.
     * @throws ApiError
     */
    public requestApplicationRecoveryContractApproval({
        applicationId,
        requestBody,
        xTenantId,
    }: {
        applicationId: string,
        requestBody: RequestRecoveryContractApprovalRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContractApproval> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/applications/{applicationId}/recovery-contract:request-approval',
            path: {
                'applicationId': applicationId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
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
     * Approve an exact contract version using a second administrator
     * @returns RecoveryContractApproval Approved contract-version evidence.
     * @throws ApiError
     */
    public approveApplicationRecoveryContract({
        applicationId,
        approvalId,
        xTenantId,
    }: {
        applicationId: string,
        approvalId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContractApproval> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:approve',
            path: {
                'applicationId': applicationId,
                'approvalId': approvalId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Reject a pending exact-version contract approval
     * @returns RecoveryContractApproval Rejected contract-version decision.
     * @throws ApiError
     */
    public rejectApplicationRecoveryContract({
        applicationId,
        approvalId,
        xTenantId,
    }: {
        applicationId: string,
        approvalId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RecoveryContractApproval> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:reject',
            path: {
                'applicationId': applicationId,
                'approvalId': approvalId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
}
