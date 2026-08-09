/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CompleteRecoveryGameDayRequest = {
    observedRtoSeconds: number;
    observedRpoSeconds: number;
    dataLossRecords: number;
    detectionTimeSeconds?: number;
    failoverTimeSeconds?: number;
    staleOperationCount?: number;
    userImpactCount?: number;
    manualSteps?: number;
    runbookAccuracyPercent?: number;
    runnerEvidenceHash?: string;
    recoveryConfirmed?: boolean;
};
