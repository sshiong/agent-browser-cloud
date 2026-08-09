/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AuditExportManifest } from '../models/AuditExportManifest.js';
import type { ClaimRuntimeValidationJobRequest } from '../models/ClaimRuntimeValidationJobRequest.js';
import type { CompleteRecoveryGameDayRequest } from '../models/CompleteRecoveryGameDayRequest.js';
import type { CompleteRuntimeValidationJobRequest } from '../models/CompleteRuntimeValidationJobRequest.js';
import type { CompleteRuntimeValidationRequest } from '../models/CompleteRuntimeValidationRequest.js';
import type { ComplianceSnapshot } from '../models/ComplianceSnapshot.js';
import type { CostRate } from '../models/CostRate.js';
import type { CreateCostRateRequest } from '../models/CreateCostRateRequest.js';
import type { CreateDeletionReceiptRequest } from '../models/CreateDeletionReceiptRequest.js';
import type { DeletionReceipt } from '../models/DeletionReceipt.js';
import type { EnterpriseOverview } from '../models/EnterpriseOverview.js';
import type { EnterpriseRegion } from '../models/EnterpriseRegion.js';
import type { ErrorBudget } from '../models/ErrorBudget.js';
import type { FailRuntimeValidationJobRequest } from '../models/FailRuntimeValidationJobRequest.js';
import type { LicenseInventory } from '../models/LicenseInventory.js';
import type { MediaQuota } from '../models/MediaQuota.js';
import type { RecordServiceLevelEventRequest } from '../models/RecordServiceLevelEventRequest.js';
import type { RecoveryGameDay } from '../models/RecoveryGameDay.js';
import type { ReleaseFreeze } from '../models/ReleaseFreeze.js';
import type { RetentionPolicy } from '../models/RetentionPolicy.js';
import type { RuntimeValidation } from '../models/RuntimeValidation.js';
import type { RuntimeValidationJob } from '../models/RuntimeValidationJob.js';
import type { RuntimeValidationJobClaim } from '../models/RuntimeValidationJobClaim.js';
import type { RuntimeValidationJobClaimRequest } from '../models/RuntimeValidationJobClaimRequest.js';
import type { SessionCostExplanation } from '../models/SessionCostExplanation.js';
import type { SlaExclusion } from '../models/SlaExclusion.js';
import type { StartRecoveryGameDayRequest } from '../models/StartRecoveryGameDayRequest.js';
import type { StartRuntimeValidationMatrixRequest } from '../models/StartRuntimeValidationMatrixRequest.js';
import type { StartRuntimeValidationRequest } from '../models/StartRuntimeValidationRequest.js';
import type { UpsertLicenseInventoryRequest } from '../models/UpsertLicenseInventoryRequest.js';
import type { UpsertMediaQuotaRequest } from '../models/UpsertMediaQuotaRequest.js';
import type { UpsertRegionRequest } from '../models/UpsertRegionRequest.js';
import type { UpsertRetentionPolicyRequest } from '../models/UpsertRetentionPolicyRequest.js';
import type { UpsertSlaExclusionRequest } from '../models/UpsertSlaExclusionRequest.js';
import type { UpsertSloPolicyRequest } from '../models/UpsertSloPolicyRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class EnterpriseService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * Read tenant enterprise operations state
     * @returns EnterpriseOverview Runtime validation, cost, SLO, retention, compliance and DR overview.
     * @throws ApiError
     */
    public getEnterpriseOverview({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<EnterpriseOverview> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/overview',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * List Build-bound Runtime Validation runs
     * @returns RuntimeValidation Validation runs.
     * @throws ApiError
     */
    public listRuntimeValidations(): CancelablePromise<Array<RuntimeValidation>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/runtime-validations',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Start a Build/environment/dataset-bound Runtime Validation
     * @returns RuntimeValidation Validation started.
     * @throws ApiError
     */
    public startRuntimeValidation({
        requestBody,
    }: {
        requestBody: StartRuntimeValidationRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validations',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Commit immutable Runtime Validation evidence
     * @returns RuntimeValidation Validation completed.
     * @throws ApiError
     */
    public completeRuntimeValidation({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: CompleteRuntimeValidationRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validations/{validationId}:complete',
            path: {
                'validationId': validationId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Enqueue an immutable browser/OS Runtime Validation matrix
     * Requires PLATFORM_ADMIN. Every matrix cell becomes an independently leased job.
     * @returns RuntimeValidation Matrix cells durably enqueued.
     * @throws ApiError
     */
    public startRuntimeValidationMatrix({
        requestBody,
    }: {
        requestBody: StartRuntimeValidationMatrixRequest,
    }): CancelablePromise<Array<RuntimeValidation>> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-matrices',
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
     * Claim one compatible Runtime Validation job with a fenced lease
     * Requires the dedicated VALIDATION_WORKER role. A claim token is returned only once.
     * @returns RuntimeValidationJobClaim Compatible job claimed.
     * @throws ApiError
     */
    public claimRuntimeValidationJob({
        requestBody,
    }: {
        requestBody: ClaimRuntimeValidationJobRequest,
    }): CancelablePromise<RuntimeValidationJobClaim> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs:claim',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * ACK execution start for a claimed Runtime Validation job
     * @returns RuntimeValidationJob Job entered EXECUTING.
     * @throws ApiError
     */
    public startClaimedRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: RuntimeValidationJobClaimRequest,
    }): CancelablePromise<RuntimeValidationJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:start',
            path: {
                'validationId': validationId,
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
     * Renew a fenced Runtime Validation Worker lease
     * @returns RuntimeValidationJob Lease renewed.
     * @throws ApiError
     */
    public heartbeatRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: RuntimeValidationJobClaimRequest,
    }): CancelablePromise<RuntimeValidationJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:heartbeat',
            path: {
                'validationId': validationId,
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
     * ACK and atomically commit a Runtime Validation Worker result
     * @returns RuntimeValidation Result committed and Runtime Build status updated.
     * @throws ApiError
     */
    public completeRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: CompleteRuntimeValidationJobRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:complete',
            path: {
                'validationId': validationId,
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
     * Reject a Runtime Validation attempt and retry or quarantine the build
     * @returns RuntimeValidation Failure durably requeued or finalized.
     * @throws ApiError
     */
    public failRuntimeValidationJob({
        validationId,
        requestBody,
    }: {
        validationId: string,
        requestBody: FailRuntimeValidationJobRequest,
    }): CancelablePromise<RuntimeValidation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/runtime-validation-jobs/{validationId}:fail',
            path: {
                'validationId': validationId,
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
     * List versioned scheduler cost rates
     * @returns CostRate Cost rates.
     * @throws ApiError
     */
    public listEnterpriseCostRates(): CancelablePromise<Array<CostRate>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/cost-rates',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Create an immutable effective-dated cost rate
     * @returns CostRate Cost rate created.
     * @throws ApiError
     */
    public createEnterpriseCostRate({
        requestBody,
    }: {
        requestBody: CreateCostRateRequest,
    }): CancelablePromise<CostRate> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/cost-rates',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Recompute the hourly Session cost from its Placement and pricing version
     * @returns SessionCostExplanation Cost components and total.
     * @throws ApiError
     */
    public explainSessionCost({
        sessionId,
        xTenantId,
    }: {
        sessionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<SessionCostExplanation> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/sessions/{sessionId}/cost-explanation',
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
     * Read independent tenant encoder-stream and bitrate limits
     * @returns MediaQuota Media quota and current active usage.
     * @throws ApiError
     */
    public getTenantMediaQuota(): CancelablePromise<MediaQuota> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/media-quota',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Configure independent tenant encoder-stream and bitrate limits
     * @returns MediaQuota Updated media quota and current usage.
     * @throws ApiError
     */
    public upsertTenantMediaQuota({
        requestBody,
    }: {
        requestBody: UpsertMediaQuotaRequest,
    }): CancelablePromise<MediaQuota> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/media-quota',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                503: `A required capacity or dependency is temporarily unavailable.`,
            },
        });
    }
    /**
     * Configure the tenant SLO and error-budget window
     * @returns ErrorBudget Current error budget.
     * @throws ApiError
     */
    public upsertSloPolicy({
        requestBody,
        xTenantId,
    }: {
        requestBody: UpsertSloPolicyRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ErrorBudget> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/slo-policy',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Calculate the tenant error budget
     * @returns ErrorBudget Current error budget.
     * @throws ApiError
     */
    public getErrorBudget({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ErrorBudget> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/error-budget',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Read the authoritative Error Budget release gate
     * Returns the PostgreSQL-backed automatic Runtime promotion freeze state. Emergency Runtime disable operations remain available while the promotion gate is frozen.
     *
     * @returns ReleaseFreeze Current automatic release gate and hysteresis state.
     * @throws ApiError
     */
    public getReleaseFreezeState({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ReleaseFreeze> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/release-freeze',
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
     * Record a bounded service-level observation
     * @returns ErrorBudget Recalculated error budget.
     * @throws ApiError
     */
    public recordServiceLevelEvent({
        requestBody,
        xTenantId,
    }: {
        requestBody: RecordServiceLevelEventRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<ErrorBudget> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/service-level-events',
            headers: {
                'X-Tenant-Id': xTenantId,
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
     * List explicit contract exclusions used by Error Budget accounting
     * @returns SlaExclusion SLA exclusions.
     * @throws ApiError
     */
    public listSlaExclusions(): CancelablePromise<Array<SlaExclusion>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/sla-exclusions',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Configure an explicit SLA exclusion
     * @returns SlaExclusion Updated SLA exclusion.
     * @throws ApiError
     */
    public upsertSlaExclusion({
        exclusionCode,
        requestBody,
    }: {
        exclusionCode: string,
        requestBody: UpsertSlaExclusionRequest,
    }): CancelablePromise<SlaExclusion> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/sla-exclusions/{exclusionCode}',
            path: {
                'exclusionCode': exclusionCode,
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
     * List tenant retention, residency and legal-hold policies
     * @returns RetentionPolicy Retention policies.
     * @throws ApiError
     */
    public listRetentionPolicies({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<Array<RetentionPolicy>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/retention-policies',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Configure a tenant data-class retention policy
     * @returns RetentionPolicy Retention policy.
     * @throws ApiError
     */
    public upsertRetentionPolicy({
        requestBody,
        xTenantId,
    }: {
        requestBody: UpsertRetentionPolicyRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RetentionPolicy> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/retention-policies',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Delete an eligible retained object and generate a tamper-evident receipt
     * @returns DeletionReceipt Deletion receipt.
     * @throws ApiError
     */
    public createRetentionDeletionReceipt({
        requestBody,
    }: {
        requestBody: CreateDeletionReceiptRequest,
    }): CancelablePromise<DeletionReceipt> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/retention-deletion-receipts',
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
     * List Runtime, Extension, Service and SDK license evidence
     * @returns LicenseInventory License inventory.
     * @throws ApiError
     */
    public listLicenseInventory(): CancelablePromise<Array<LicenseInventory>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/license-inventory',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Upsert a license evidence record
     * @returns LicenseInventory License evidence.
     * @throws ApiError
     */
    public upsertLicenseInventory({
        componentId,
        requestBody,
    }: {
        componentId: string,
        requestBody: UpsertLicenseInventoryRequest,
    }): CancelablePromise<LicenseInventory> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/license-inventory/{componentId}',
            path: {
                'componentId': componentId,
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
     * Generate a signed manifest for a contiguous tenant audit range
     * @returns AuditExportManifest Signed audit export manifest.
     * @throws ApiError
     */
    public generateAuditExportManifest({
        fromSequence,
        toSequence,
    }: {
        fromSequence?: number,
        toSequence?: number,
    }): CancelablePromise<AuditExportManifest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/audit-exports',
            query: {
                'fromSequence': fromSequence,
                'toSequence': toSequence,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * List authoritative Region and replication state
     * @returns EnterpriseRegion Regions.
     * @throws ApiError
     */
    public listEnterpriseRegions(): CancelablePromise<Array<EnterpriseRegion>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/regions',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Register or verify a Region
     * @returns EnterpriseRegion Region state.
     * @throws ApiError
     */
    public upsertEnterpriseRegion({
        regionId,
        requestBody,
    }: {
        regionId: string,
        requestBody: UpsertRegionRequest,
    }): CancelablePromise<EnterpriseRegion> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/regions/{regionId}',
            path: {
                'regionId': regionId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * List measured Recovery GameDays
     * @returns RecoveryGameDay Recovery GameDays.
     * @throws ApiError
     */
    public listRecoveryGameDays(): CancelablePromise<Array<RecoveryGameDay>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/recovery-gamedays',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Start an RTO/RPO-bound Recovery GameDay
     * @returns RecoveryGameDay GameDay started.
     * @throws ApiError
     */
    public startRecoveryGameDay({
        requestBody,
    }: {
        requestBody: StartRecoveryGameDayRequest,
    }): CancelablePromise<RecoveryGameDay> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gamedays',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Complete a Recovery GameDay with measured evidence
     * @returns RecoveryGameDay GameDay completed.
     * @throws ApiError
     */
    public completeRecoveryGameDay({
        gameDayId,
        requestBody,
    }: {
        gameDayId: string,
        requestBody: CompleteRecoveryGameDayRequest,
    }): CancelablePromise<RecoveryGameDay> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gamedays/{gameDayId}:complete',
            path: {
                'gameDayId': gameDayId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Generate a hash-bound tenant compliance snapshot
     * @returns ComplianceSnapshot Compliance snapshot.
     * @throws ApiError
     */
    public generateComplianceSnapshot({
        xTenantId,
        framework = 'SOC2',
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        framework?: string,
    }): CancelablePromise<ComplianceSnapshot> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/compliance-snapshots',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'framework': framework,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
}
