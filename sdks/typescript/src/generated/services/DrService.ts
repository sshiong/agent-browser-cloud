/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ClaimRecoveryGameDayJobRequest } from '../models/ClaimRecoveryGameDayJobRequest.js';
import type { CompleteRecoveryGameDayJobRequest } from '../models/CompleteRecoveryGameDayJobRequest.js';
import type { CompleteRecoveryGameDayRequest } from '../models/CompleteRecoveryGameDayRequest.js';
import type { EnterpriseRegion } from '../models/EnterpriseRegion.js';
import type { FailRecoveryGameDayJobRequest } from '../models/FailRecoveryGameDayJobRequest.js';
import type { RecoveryGameDay } from '../models/RecoveryGameDay.js';
import type { RecoveryGameDayJob } from '../models/RecoveryGameDayJob.js';
import type { RecoveryGameDayJobClaim } from '../models/RecoveryGameDayJobClaim.js';
import type { RecoveryGameDayJobClaimRequest } from '../models/RecoveryGameDayJobClaimRequest.js';
import type { StartRecoveryGameDayRequest } from '../models/StartRecoveryGameDayRequest.js';
import type { UpdateRecoveryGameDayStageRequest } from '../models/UpdateRecoveryGameDayStageRequest.js';
import type { UpsertRegionRequest } from '../models/UpsertRegionRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class DrService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
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
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Get one measured or automated Recovery GameDay and its durable job state
     * @returns RecoveryGameDay Recovery GameDay state.
     * @throws ApiError
     */
    public getRecoveryGameDay({
        gameDayId,
    }: {
        gameDayId: string,
    }): CancelablePromise<RecoveryGameDay> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/recovery-gamedays/{gameDayId}',
            path: {
                'gameDayId': gameDayId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Request a fenced automated GameDay abort and mandatory recovery
     * @returns RecoveryGameDay Abort request accepted or queued job aborted.
     * @throws ApiError
     */
    public abortRecoveryGameDay({
        gameDayId,
    }: {
        gameDayId: string,
    }): CancelablePromise<RecoveryGameDay> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gamedays/{gameDayId}:abort',
            path: {
                'gameDayId': gameDayId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Claim a capability-matched GameDay job with an opaque fenced lease token
     * @returns RecoveryGameDayJobClaim A matching job was claimed.
     * @throws ApiError
     */
    public claimRecoveryGameDayJob({
        requestBody,
    }: {
        requestBody: ClaimRecoveryGameDayJobRequest,
    }): CancelablePromise<RecoveryGameDayJobClaim> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gameday-jobs:claim',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Start a claimed GameDay or recovery-only execution
     * @returns RecoveryGameDayJob Durable executing job state.
     * @throws ApiError
     */
    public startRecoveryGameDayJob({
        gameDayId,
        requestBody,
    }: {
        gameDayId: string,
        requestBody: RecoveryGameDayJobClaimRequest,
    }): CancelablePromise<RecoveryGameDayJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:start',
            path: {
                'gameDayId': gameDayId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Renew the currently fenced GameDay worker lease
     * @returns RecoveryGameDayJob Renewed durable lease state.
     * @throws ApiError
     */
    public heartbeatRecoveryGameDayJob({
        gameDayId,
        requestBody,
    }: {
        gameDayId: string,
        requestBody: RecoveryGameDayJobClaimRequest,
    }): CancelablePromise<RecoveryGameDayJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:heartbeat',
            path: {
                'gameDayId': gameDayId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Advance the monotonic GameDay execution and recovery stage
     * @returns RecoveryGameDayJob Updated durable job state.
     * @throws ApiError
     */
    public updateRecoveryGameDayJobStage({
        gameDayId,
        requestBody,
    }: {
        gameDayId: string,
        requestBody: UpdateRecoveryGameDayStageRequest,
    }): CancelablePromise<RecoveryGameDayJob> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:stage',
            path: {
                'gameDayId': gameDayId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * ACK measured evidence and commit only after confirmed recovery
     * @returns RecoveryGameDay Committed GameDay and evidence state.
     * @throws ApiError
     */
    public completeRecoveryGameDayJob({
        gameDayId,
        requestBody,
    }: {
        gameDayId: string,
        requestBody: CompleteRecoveryGameDayJobRequest,
    }): CancelablePromise<RecoveryGameDay> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:complete',
            path: {
                'gameDayId': gameDayId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * Fail or requeue an execution while enforcing recovery after injection
     * @returns RecoveryGameDay Durable failed, aborted, requeued or recovery-required state.
     * @throws ApiError
     */
    public failRecoveryGameDayJob({
        gameDayId,
        requestBody,
    }: {
        gameDayId: string,
        requestBody: FailRecoveryGameDayJobRequest,
    }): CancelablePromise<RecoveryGameDay> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:fail',
            path: {
                'gameDayId': gameDayId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                409: `State or idempotency conflict.`,
            },
        });
    }
}
