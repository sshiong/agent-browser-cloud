/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CompleteRecoveryGameDayRequest } from '../models/CompleteRecoveryGameDayRequest.js';
import type { EnterpriseRegion } from '../models/EnterpriseRegion.js';
import type { RecoveryGameDay } from '../models/RecoveryGameDay.js';
import type { StartRecoveryGameDayRequest } from '../models/StartRecoveryGameDayRequest.js';
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
            },
        });
    }
}
