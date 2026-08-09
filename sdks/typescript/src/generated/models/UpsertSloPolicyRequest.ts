/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UpsertSloPolicyRequest = {
    availabilityTarget: number;
    latencyP95TargetMs: number;
    windowMinutes: number;
    releaseFreezeEnabled?: boolean;
    releaseFreezeBurnRateThreshold?: number;
    releaseRecoveryBurnRateThreshold?: number;
    releaseFreezeWindowMinutes?: number;
    releaseRecoveryStableMinutes?: number;
};
