/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecoveryGameDayBlastRadius } from './RecoveryGameDayBlastRadius.js';
import type { RecoveryGameDayJob } from './RecoveryGameDayJob.js';
export type RecoveryGameDay = {
    gameDayId: string;
    scenario: string;
    sourceRegion: string;
    targetRegion: string;
    state: 'QUEUED' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ABORTED';
    rtoTargetSeconds: number;
    rpoTargetSeconds: number;
    observedRtoSeconds: number | null;
    observedRpoSeconds: number | null;
    dataLossRecords: number | null;
    evidenceHash: string | null;
    startedBy: string;
    startedAt: string;
    completedAt: string | null;
    executionMode: 'MANUAL' | 'AUTO';
    environment: 'TEST' | 'STAGING' | 'PRODUCTION';
    blastRadius: (RecoveryGameDayBlastRadius | null);
    maximumDurationSeconds: number;
    approvalRequestId: string | null;
    currentStage: string;
    abortRequested: boolean;
    recoveryConfirmed: boolean | null;
    failureCode: string | null;
    job: (RecoveryGameDayJob | null);
};
