/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RecoveryGameDay = {
    gameDayId: string;
    scenario: string;
    sourceRegion: string;
    targetRegion: string;
    state: 'RUNNING' | 'PASSED' | 'FAILED';
    rtoTargetSeconds: number;
    rpoTargetSeconds: number;
    observedRtoSeconds: number | null;
    observedRpoSeconds: number | null;
    dataLossRecords: number | null;
    evidenceHash: string | null;
    startedBy: string;
    startedAt: string;
    completedAt: string | null;
};
